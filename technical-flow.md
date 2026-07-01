# Crypto Payment Platform — Technical Flow (P2P Transfer)

> Mô tả luồng kỹ thuật cấp component/thread cho use case P2P Transfer — góc nhìn khác với `use-cases.md` (nghiệp vụ). File này tập trung vào: thread nào chạy gì, component nào gọi component nào, transaction boundary ở đâu.

---

## Tổng quan các thread context tham gia

| Thread | Component | Vai trò |
|---|---|---|
| HTTP thread (Tomcat) | `PaymentController` | Nhận request, ghi transaction + outbox, trả response ngay (không chờ Saga) |
| Scheduler thread | `OutboxPublisherJob` (mỗi service có 1 job riêng) | Đẩy outbox event lên Kafka, có Distributed Lock |
| Kafka Consumer thread | `@KafkaListener` (mỗi service) | Xử lý command/event, cập nhật DB, ghi outbox tiếp theo |

Kiến trúc này đảm bảo **không request HTTP nào phải chờ toàn bộ Saga chạy xong** — client nhận `202 Accepted` ngay, phần còn lại xử lý bất đồng bộ hoàn toàn qua Kafka.

---

## Giai đoạn 1 — HTTP Request (HTTP thread)

```
Client → POST /api/payments/transfer
Header: Idempotency-Key: <uuid>
Body: { senderId, receiverId, amount, currency }
```

`PaymentController` → `PaymentService.transfer()`:

1. Check `Idempotency-Key` trong bảng `payment_idempotency_keys` (query đồng bộ)
2. Validate: `senderId != receiverId`, `amount > 0`, `currency` không rỗng
3. Mở `@Transactional` — trong cùng 1 DB transaction:
   - `INSERT INTO transactions` (`status=INITIATED`, `saga_state=DEBIT_PENDING`)
   - `INSERT INTO outbox_events` (`event_type=DebitCommand`, `payload={transactionId, senderId, amount, currency}`)
   - `INSERT INTO payment_idempotency_keys`
4. Commit → trả HTTP `202 Accepted` về client

> DB transaction đảm bảo `transactions` và `outbox_events` cùng sống hoặc cùng chết — đây là lúc Outbox Pattern phát huy tác dụng.

---

## Giai đoạn 2 — Outbox Publisher (Scheduler thread, độc lập HTTP thread)

`OutboxPublisherJob` — `@Scheduled(fixedDelay = 1000)`:

```
1. Acquire Redis lock "lock:outbox-publisher" (SETNX, TTL 30s)
   → nếu instance khác đang giữ lock, return ngay
2. SELECT * FROM outbox_events WHERE status='PENDING' ORDER BY id LIMIT 100
3. Với mỗi event:
   a. kafkaTemplate.send(topic="wallet.commands", key=transactionId, payload)
   b. UPDATE outbox_events SET status='PUBLISHED' WHERE id=?
4. Release Redis lock
```

`DebitCommand` giờ nằm trong Kafka topic `wallet.commands`, partition route theo `key=transactionId` — đảm bảo mọi message cùng 1 transaction luôn vào cùng 1 partition, giữ đúng thứ tự xử lý.

---

## Giai đoạn 3 — wallet-service consume DebitCommand (Kafka Consumer thread)

`WalletCommandListener` — `@KafkaListener(topics="wallet.commands", groupId="wallet-service-group")`:

```
1. Deserialize message → DebitCommand{transactionId, userId, amount}
2. Redis SETNX "debit:{transactionId}:{userId}"
   → nếu key đã tồn tại (duplicate delivery) → skip, không xử lý lại
3. @Transactional:
   a. SELECT wallet WHERE user_id=? (load kèm @Version)
   b. Check balance >= amount → không đủ thì throw InsufficientBalanceException
   c. wallet.balance -= amount (JPA check @Version khi save;
      OptimisticLockException → @Retryable retry tối đa 3 lần)
   d. INSERT INTO wallet_transaction_logs (balance_before, balance_after)
   e. INSERT INTO wallet_outbox_events (event_type=DebitCompleted)
4. Commit → offset commit sau khi DB transaction thành công
   (tránh mất event nếu service crash giữa chừng)
```

`wallet_outbox_events` giờ có 1 row `DebitCompleted` chờ `OutboxPublisherJob` riêng của wallet-service đẩy lên topic `wallet.events`.

---

## Giai đoạn 4 — payment-service consume DebitCompleted (Kafka Consumer thread)

`WalletEventListener` — `@KafkaListener(topics="wallet.events")`:

```
1. Deserialize → DebitCompleted{transactionId}
2. @Transactional:
   a. SELECT transaction WHERE id=transactionId
   b. UPDATE saga_state = 'DEBIT_COMPLETED'   ← state trung gian, resume point khi crash
   c. INSERT INTO outbox_events (event_type=CreditCommand,
      payload={transactionId, receiverId, amount, currency})
   d. UPDATE saga_state = 'CREDIT_PENDING'
   e. INSERT INTO saga_logs (from_state='DEBIT_PENDING', to_state='CREDIT_PENDING',
      event_type='DebitCompleted')
3. Commit
```

`OutboxPublisherJob` (payment-service) nhặt `CreditCommand`, publish sang `wallet.commands` — lặp lại Giai đoạn 2 → 3, lần này wallet-service consume `CreditCommand`, publish `CreditCompleted`.

---

## Giai đoạn 5 — payment-service consume CreditCompleted → kết thúc Saga

```
1. UPDATE transactions SET status='COMPLETED', saga_state='COMPLETED'
2. INSERT INTO transaction_history (2 rows: sender SENT, receiver RECEIVED)   ← CQRS write
3. INSERT INTO saga_logs
4. INSERT outbox_events (event_type=TransferCompleted) → publish topic "payment.events"
```

`notification-service` consume `payment.events` → gửi thông báo cho cả 2 user. Saga kết thúc.

---

## Biến thể lỗi — Sad Path 2 (Partial Failure → Compensating Transaction)

Ở Giai đoạn 4 (biến thể), nếu wallet-service xử lý `CreditCommand` thất bại (B bị `FROZEN`):

```
wallet-service:
  publish CreditFailed → topic wallet.events

payment-service consume CreditFailed:
  UPDATE saga_state = 'COMPENSATING'
  INSERT outbox_events (event_type=DebitReverseCommand)
  → publish → wallet-service consume, hoàn tiền A
     với idempotency key "debit-reverse:{transactionId}:{userId}"
  → publish DebitReversed
  → payment-service consume DebitReversed
  → UPDATE status='REVERSED', saga_state='REVERSED'
```

Nếu `DebitReverseCommand` retry hết số lần vẫn fail → `saga_state='COMPENSATION_FAILED'`, đẩy sang Kafka topic `wallet.dlq` (Dead Letter Queue) để can thiệp thủ công (human intervention).

---

## Lưu ý khi implement (cho Kiro)

- Mỗi `@KafkaListener` xử lý xong phải **commit offset sau khi DB transaction thành công** — không commit offset trước, tránh mất event khi crash giữa chừng
- `OutboxPublisherJob` là 1 class riêng cho từng service (`user-service`, `wallet-service`, `payment-service`) — không dùng chung 1 job, vì mỗi service có bảng outbox và Kafka topic đích khác nhau
- Redis distributed lock (`lock:outbox-publisher`) là bắt buộc nếu service chạy nhiều instance — tránh publish trùng event
- `@Retryable` cho Optimistic Locking nên dùng exponential backoff, không retry ngay lập tức để giảm contention
- Toàn bộ Kafka message nên set `key = transactionId` khi publish — đảm bảo cùng transaction luôn vào cùng partition, giữ đúng thứ tự xử lý giữa các bước Saga
