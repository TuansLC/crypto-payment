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

### Quy ước payload command (thống nhất toàn hệ thống)

Mọi command gửi tới wallet-service dùng chung khung field, `userId` là **chủ ví** cần tác động:

```json
{ "transactionId": "...", "userId": "...", "amount": "...", "currency": "USDT" }
```

- `DebitCommand`        → `userId = senderId`
- `CreditCommand`       → `userId = receiverId`
- `DebitReverseCommand` → `userId = senderId`

> `currency` là **bắt buộc** trong mọi command: vì `wallets` là multi-currency (`UNIQUE(user_id, currency)`), wallet-service cần cả `userId` lẫn `currency` mới xác định đúng ví.

### Event envelope chuẩn (áp dụng cho MỌI event/command của mọi service)

Mọi message publish lên Kafka đều bọc trong một envelope thống nhất — đây là nền tảng cho trace log tập trung:

```json
{
  "eventId":       "uuid",        // id duy nhất của event này (idempotency)
  "correlationId": "uuid",        // XUYÊN SUỐT 1 giao dịch qua mọi service — key để trace
  "causationId":   "uuid|null",   // eventId của event đã GÂY RA event này (cây nhân quả)
  "eventType":     "DebitCompleted",
  "sourceService": "wallet",
  "occurredAt":    "2026-07-02T10:00:00Z",
  "payload":       { "transactionId": "...", "userId": "...", "amount": "...", "currency": "USDT" }
}
```

Quy tắc lan truyền:
- `correlationId` sinh **một lần** ở API controller khi nhận request (nếu client không gửi kèm), rồi **copy nguyên vẹn** qua mọi bước Saga và mọi Kafka header. Nhờ đó truy 1 lệnh: `WHERE correlation_id = ?`.
- `causationId` của event mới = `eventId` của event vừa xử lý sinh ra nó → dựng được chuỗi nhân quả DebitCommand → DebitCompleted → CreditCommand...
- Nên đặt `correlationId`/`causationId`/`eventId` ở **Kafka header** (đồng thời lưu trong envelope) để consumer đọc metadata không cần parse payload.

**audit-service** (`@KafkaListener` group-id `audit-service-group`, subscribe **tất cả** topic) đọc envelope này và INSERT vào `event_store` (audit_db), idempotent theo `eventId` (UNIQUE → bắt duplicate-key khi Kafka giao trùng). Đây là nơi trace toàn bộ hành trình mà **không** phá database-per-service (chỉ audit-service sở hữu bảng, các service khác chỉ publish lên Kafka như thường).

---

## Giai đoạn 1 — HTTP Request (HTTP thread)

```
Client → POST /api/payments/transfer
Header: Idempotency-Key: <uuid>
Body: { senderId, receiverId, amount, currency }
```

`PaymentController` → `PaymentService.transfer()`:

1. Validate: `senderId != receiverId`, `amount > 0`, `currency` không rỗng
2. Mở `@Transactional` — trong cùng 1 DB transaction:
   - `INSERT INTO transactions` (`status=INITIATED`, `saga_state=DEBIT_PENDING`)
   - `INSERT INTO outbox_events` (`event_type=DebitCommand`, `payload={transactionId, userId: senderId, amount, currency}`)
   - `INSERT INTO payment_idempotency_keys (idempotency_key, transaction_id, ...)`
3. Commit → trả HTTP `202 Accepted` (kèm `transactionId`) về client

> DB transaction đảm bảo `transactions`, `outbox_events` và `payment_idempotency_keys` cùng sống hoặc cùng chết — đây là lúc Outbox Pattern phát huy tác dụng.

**Chống duplicate request bằng PRIMARY KEY, KHÔNG dùng check-then-insert:**
`Idempotency-Key` là PK của `payment_idempotency_keys`. Đừng "SELECT xem key tồn tại chưa rồi mới INSERT" — 2 request song song có thể cùng vượt qua bước SELECT (race TOCTOU). Thay vào đó cứ **INSERT thẳng**; nếu dính `DuplicateKeyException` → nghĩa là request đã được nhận trước đó → đọc `payment_idempotency_keys.result` và trả về kết quả cũ (`200 OK`), không tạo transaction thứ 2. PK constraint là điểm atomic thật sự.

**Client lấy kết quả cuối cùng thế nào?**
202 chỉ nghĩa là "đã nhận, đang xử lý". Client theo dõi trạng thái cuối bằng **polling `GET /api/payments/{transactionId}`** (hoặc WebSocket/SSE nếu muốn real-time) cho tới khi `status` chuyển `COMPLETED` / `FAILED` / `REVERSED`.

---

## Giai đoạn 2 — Outbox Publisher (Scheduler thread, độc lập HTTP thread)

`OutboxPublisherJob` — `@Scheduled(fixedDelay = 1000)`. **Cách tiếp cận mặc định: `SELECT ... FOR UPDATE SKIP LOCKED`** (không cần Distributed Lock ở vòng ngoài):

```
1. @Transactional:
   SELECT * FROM outbox_events
   WHERE status='PENDING'
   ORDER BY id
   LIMIT 100
   FOR UPDATE SKIP LOCKED;      ← row đã bị worker khác khóa → bỏ qua, lấy row khác
2. Với mỗi event:
   a. future = kafkaTemplate.send(topic, key=transactionId, payload)
   b. future.get(timeout)   ← CHỜ broker ack thành công rồi mới sang bước c
   c. UPDATE outbox_events SET status='PUBLISHED', published_at=NOW() WHERE id=?
      (nếu send lỗi → UPDATE retry_count++, error_message; giữ PENDING để lần sau thử lại)
3. Commit (nhả row lock)
```

**Vì sao `SKIP LOCKED` thay vì Distributed Lock (Redisson)?**
Distributed lock toàn cục chỉ cho **1 instance** xử lý outbox tại một thời điểm → thắt nút cổ chai khi lượng giao dịch tăng vọt. `SKIP LOCKED` cho **nhiều worker chạy song song**: mỗi worker khóa và xử lý một tập row khác nhau, không giẫm chân nhau → throughput cao hơn hẳn, lại bớt một critical dependency (Redis).

**Đánh đổi phải nêu rõ (đừng giấu):** `SKIP LOCKED` **từ bỏ global FIFO ordering** — thứ tự publish tuyệt đối giữa các row (mà `BIGSERIAL` bảo đảm) không còn giữ khi nhiều worker publish song song. Với Saga điều này **chấp nhận được** vì:
- Ta chỉ cần ordering trong phạm vi **1 aggregate (`transactionId`)**, không cần global. Các event cùng một transaction cách nhau về thời gian (DebitCommand → đợi DebitCompleted → mới sinh CreditCommand) nên hiếm khi nằm trong outbox cùng lúc để bị 2 worker giành.
- Lớp bảo hiểm thật sự là `key=transactionId`: Kafka đưa mọi message cùng key vào **cùng partition**, consumer xử lý **tuần tự trong partition** → thứ tự tiêu thụ per-transaction luôn đúng kể cả khi thứ tự publish toàn cục bị xáo.

> Chỉ khi bài toán thật sự cần **strict global ordering** mới quay lại mô hình single-worker + Redisson RLock (watchdog gia hạn lease). Với hệ thanh toán này, đó là over-constraint.

**Hai điểm kỹ thuật bắt buộc nhớ:**
- **`kafkaTemplate.send()` là bất đồng bộ** — trả `CompletableFuture`. Phải chờ ack (`future.get()` hoặc callback) trước khi đánh dấu `PUBLISHED`. Nếu mark PUBLISHED trước khi broker nhận → mất event khi Kafka lỗi.
- **Publisher là at-least-once:** nếu crash giữa bước b (đã gửi) và bước c (chưa kịp mark PUBLISHED), vòng sau sẽ publish lại → event trùng. Đây là lý do **consumer bắt buộc idempotent** (xem Giai đoạn 3). Không cố ép exactly-once ở publisher.

`DebitCommand` giờ nằm trong Kafka topic `wallet.commands`, partition route theo `key=transactionId` — đảm bảo mọi message cùng 1 transaction luôn vào cùng 1 partition, giữ đúng thứ tự xử lý.

---

## Giai đoạn 3 — wallet-service consume DebitCommand (Kafka Consumer thread)

`WalletCommandListener` — `@KafkaListener(topics="wallet.commands", groupId="wallet-service-group")`:

```
0. (fast-path, tùy chọn) Redis SETNX "debit:{transactionId}:{userId}"
   → nếu đã tồn tại → nhiều khả năng duplicate → có thể skip sớm.
   ⚠️ Đây CHỈ là tối ưu chặn sớm, KHÔNG phải nguồn chân lý idempotency.

1. Deserialize → DebitCommand{transactionId, userId, amount, currency}

2. @Transactional:   ← idempotency + nghiệp vụ nằm CHUNG 1 transaction (atomic)
   a. INSERT INTO idempotency_keys (idempotency_key = 'debit:{transactionId}:{userId}')
      → nếu DuplicateKeyException → đã xử lý rồi → rollback nhẹ / bỏ qua, KHÔNG trừ tiền lần 2
   b. SELECT wallet WHERE user_id=? AND currency=?   ← BẮT BUỘC có currency (multi-currency)
   c. Check balance >= amount → không đủ → set idempotency status, publish DebitFailed
   d. wallet.balance -= amount   (JPA @Version; flush() ngay để bắt OptimisticLockException)
   e. INSERT INTO wallet_transaction_logs (balance_before, balance_after)
   f. INSERT INTO wallet_outbox_events (event_type=DebitCompleted, currency)
3. Commit → offset commit SAU KHI DB transaction thành công
   (ack sau, tránh mất event nếu service crash giữa chừng)
```

**Vì sao idempotency dùng DB table, không chỉ Redis:**
Nếu chỉ `SETNX` trước transaction rồi transaction rollback/crash → key Redis vẫn còn → redelivery bị skip → tiền **không bao giờ được trừ** (mất event). Ghi `idempotency_keys` **bên trong cùng transaction** với việc trừ tiền → key và balance commit atomic: hoặc cả hai cùng có, hoặc cả hai cùng không. Redis chỉ là lớp chặn sớm cho hiệu năng.

**Optimistic Locking + Retry đúng cách:**
- `OptimisticLockException` thường ném lúc **flush/commit**. Gọi `flush()` ngay sau bước d để exception nổ **trong** method, `@Retryable` mới bắt được.
- Đặt `@Retryable` ở method **ngoài**, gọi vào method `@Transactional(REQUIRES_NEW)` bên trong (tách bean để tránh self-invocation qua proxy). Mỗi lần retry phải mở **transaction mới** — retry trên transaction đã rollback là vô nghĩa. Dùng exponential backoff để giảm contention.

`wallet_outbox_events` giờ có 1 row `DebitCompleted` chờ `OutboxPublisherJob` riêng của wallet-service đẩy lên topic `wallet.events`.

---

## Giai đoạn 4 — payment-service consume DebitCompleted (Kafka Consumer thread)

`WalletEventListener` — `@KafkaListener(topics="wallet.events")`:

```
1. Deserialize → DebitCompleted{transactionId}
2. @Transactional:   ← MỘT transaction atomic duy nhất
   a. SELECT transaction WHERE id=transactionId
   b. UPDATE saga_state = 'CREDIT_PENDING'
   c. INSERT INTO outbox_events (event_type=CreditCommand,
      payload={transactionId, userId: receiverId, amount, currency})
   d. INSERT INTO saga_logs (from_state='DEBIT_PENDING', to_state='CREDIT_PENDING',
      event_type='DebitCompleted')
3. Commit
```

> **Không có state trung gian `DEBIT_COMPLETED`.** Vì việc cập nhật `saga_state` và ghi `outbox_events (CreditCommand)` nằm **chung 1 transaction atomic**, bài toán "crash giữa lúc nhận DebitCompleted và lúc phát CreditCommand" đã được Outbox Pattern giải quyết triệt để: hoặc cả hai cùng commit (chuyển thẳng sang `CREDIT_PENDING`), hoặc cùng rollback (giữ nguyên `DEBIT_PENDING`, sẽ được xử lý lại nhờ consumer idempotent). Thêm một state trung gian trong cùng transaction là dư thừa vì nó không bao giờ được commit tách biệt.

`OutboxPublisherJob` (payment-service) nhặt `CreditCommand`, publish sang `wallet.commands` — lặp lại Giai đoạn 2 → 3, lần này wallet-service consume `CreditCommand` (tìm hoặc tự tạo ví B đúng `currency` theo pattern insert-or-get), publish `CreditCompleted`.

> **Mapping idempotency key — dặn kỹ khi implement:** `CreditCommand` phải map `userId = receiverId`, để wallet-service sinh đúng key `credit:{transactionId}:{receiverId}`. Ba lệnh trên cùng một `transactionId` dùng **prefix khác nhau** nên KHÔNG đụng key nhau:
> - `DebitCommand`        → `debit:{transactionId}:{senderId}`
> - `CreditCommand`       → `credit:{transactionId}:{receiverId}`
> - `DebitReverseCommand` → `debit-reverse:{transactionId}:{senderId}`
>
> Nếu lỡ dùng chung key `txn:{transactionId}` thì `CreditCommand` sẽ bị chặn nhầm vì tưởng là duplicate của `DebitCommand` — đây là bug tinh vi cần tránh.

---

## Giai đoạn 5 — payment-service consume CreditCompleted → kết thúc Saga

```
@Transactional (atomic):
  1. UPDATE transactions SET status='COMPLETED', saga_state='COMPLETED'
  2. INSERT INTO transaction_history (2 rows: sender SENT, receiver RECEIVED)   ← CQRS write
  3. INSERT INTO saga_logs (to_state='COMPLETED')
  4. INSERT outbox_events (event_type=TransferCompleted)
Commit → OutboxPublisherJob publish sang topic "payment.events"
```

`notification-service` consume `payment.events` → gửi thông báo cho cả 2 user. Saga kết thúc.

---

## Biến thể lỗi — Sad Path 2 (Partial Failure → Compensating Transaction)

Ở Giai đoạn 4 (biến thể), nếu wallet-service xử lý `CreditCommand` thất bại (B bị `FROZEN`):

```
wallet-service:
  publish CreditFailed → topic wallet.events

payment-service consume CreditFailed (@Transactional):
  UPDATE saga_state = 'COMPENSATING'
  INSERT outbox_events (event_type=DebitReverseCommand,
    payload={transactionId, userId: senderId, amount, currency})
  → publish → wallet-service consume, hoàn tiền A
     với idempotency key "debit-reverse:{transactionId}:{userId}" (DB table, atomic)
  → publish DebitReversed
  → payment-service consume DebitReversed
  → UPDATE status='REVERSED', saga_state='REVERSED'
```

Nếu `DebitReverseCommand` retry hết số lần vẫn fail → `saga_state='COMPENSATION_FAILED'` (giữ `status='PROCESSING'` vì giao dịch chưa thực sự kết thúc), đẩy vào Dead Letter Queue để can thiệp thủ công (human intervention).

**Ghi nhận lỗi khi vào DLQ — làm đúng cách:**
- **Không đổ full stacktrace vào payload Kafka** (dài, nhiễu, phình message). Lưu **metadata có cấu trúc** ở **Kafka header** (tách khỏi payload nghiệp vụ): `errorClass`, `errorMessage` (ngắn gọn), `failedStep` (vd `DEBIT_REVERSE`), `retryCount`, `lastAttemptAt`, `transactionId`.
- **Full stacktrace để ở application log**, correlate bằng `transactionId` (MDC / trace-id).
- Đồng thời **INSERT vào bảng `dead_letter_events` (payment_db)** — Ops query SQL dễ hơn nhiều so với đọc message trong Kafka topic, và có thể build dashboard / replay từ đó. Kafka topic `wallet.dlq` giữ vai trò stream; bảng DB giữ vai trò sổ tra cứu.

---

## Lưu ý khi implement (cho Kiro)

- **Idempotency là DB-first:** ghi idempotency key vào bảng DB **trong cùng transaction** với nghiệp vụ; Redis SETNX chỉ là fast-path chặn sớm, không phải nguồn chân lý.
- **Chống duplicate HTTP request:** dựa vào PRIMARY KEY của `payment_idempotency_keys` (INSERT rồi bắt DuplicateKeyException), không dùng check-then-insert.
- Mỗi `@KafkaListener` xử lý xong phải **commit offset sau khi DB transaction thành công** — không commit offset trước, tránh mất event khi crash giữa chừng.
- **Outbox publisher chờ broker ack** trước khi mark `PUBLISHED`; chấp nhận at-least-once và dựa vào consumer idempotent để khử trùng.
- `OutboxPublisherJob` là 1 class riêng cho từng service (`user-service`, `wallet-service`, `payment-service`) — không dùng chung 1 job, vì mỗi service có bảng outbox và Kafka topic đích khác nhau.
- Distributed lock (`lock:outbox-publisher`) bắt buộc nếu service chạy nhiều instance; ưu tiên Redisson RLock (watchdog gia hạn lease) hoặc `SELECT ... FOR UPDATE SKIP LOCKED`.
- `@Retryable` cho Optimistic Locking: `flush()` để bắt được exception trong method, tách bean + `REQUIRES_NEW` để mỗi retry mở transaction mới, dùng exponential backoff.
- **Mọi command mang đủ `currency`**; wallet-service luôn query ví theo `(user_id, currency)`.
- Toàn bộ Kafka message set `key = transactionId` khi publish — đảm bảo cùng transaction luôn vào cùng partition, giữ đúng thứ tự xử lý giữa các bước Saga.
- **Event envelope chuẩn cho mọi message:** `eventId` (idempotency), `correlationId` (lan truyền xuyên suốt để trace), `causationId` (cây nhân quả), đặt ở Kafka header. `correlationId` sinh một lần ở controller, copy nguyên qua mọi bước.
- **audit-service** subscribe mọi topic bằng group-id riêng (`audit-service-group`), ghi `event_store` idempotent theo `eventId`. Không cho các service nghiệp vụ ghi chung 1 bảng — giữ database-per-service.
