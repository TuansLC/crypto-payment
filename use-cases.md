# Crypto Payment Platform — Use Cases

> Tài liệu use case đầy đủ cho 3 kịch bản nghiệp vụ chính. Dùng làm input cho Kiro để implement.

---

## Use Case 1: Đăng ký & Khởi tạo ví tự động (Onboarding)

### 1. Mục tiêu
User tạo tài khoản mới và hệ thống tự động cấp phát cho họ một ví USDT ảo với số dư bằng 0.

### 2. Tiền điều kiện (Pre-conditions)
Username và Email chưa từng tồn tại trong hệ thống.

### 3. Luồng chính (Happy Path)

| Bước | Hành động |
|---|---|
| 1 | User nhập thông tin đăng ký (Username, Email, Password) |
| 2 | `user-service` kiểm tra trùng lặp. Nếu hợp lệ, lưu User vào DB với `status = 'ACTIVE'` |
| 3 (ngầm) | `user-service` ghi event `UserRegistered` vào `user_outbox_events` (cùng transaction với bước 2) |
| 4 | `wallet-service` consume `UserRegistered`, tạo ví mới `balance = 0`, `currency = 'USDT'` |
| 5 | `notification-service` consume `UserRegistered`, gửi email chào mừng |

### 4. Ngoại lệ (Sad Path)

**4.1 — Trùng Email/Username**
Báo lỗi ngay trên UI (HTTP 409 Conflict), không ghi vào `user_outbox_events`, không sinh event.

**4.2 — wallet-service tạm thời down (bổ sung)**
Nếu `wallet-service` down đúng lúc `UserRegistered` được publish:
- Event đã nằm trong Kafka topic `user.events` từ trước đó (nhờ Outbox Pattern — event được ghi vào DB cùng transaction với User, không phụ thuộc Kafka có sẵn sàng hay không)
- Khi `wallet-service` phục hồi, consumer group tự động catch up từ offset cuối cùng chưa consume → ví vẫn được tạo, không mất event
- Đây chính là lý do cốt lõi cần Outbox Pattern thay vì gọi `kafkaTemplate.send()` trực tiếp

---

## Use Case 2: Nạp tiền (Top-up)

### 1. Mục tiêu
User nạp tiền (USDT) từ bên ngoài (thẻ tín dụng, ngân hàng) vào ví nội bộ.

### 2. Quy tắc nghiệp vụ (Business Rules)
- Số tiền nạp phải `> 0`
- Ví của user phải đang ở trạng thái `ACTIVE` (không bị `FROZEN` hay `CLOSED`)
- **(Bổ sung)** Mỗi lệnh top-up có 1 `external_reference_id` duy nhất từ cổng thanh toán — dùng để chống duplicate credit khi cổng thanh toán resend webhook

### 3. Luồng chính (Happy Path)

| Bước | Hành động |
|---|---|
| 1 | User gọi API nạp tiền với số tiền $1,000, kèm `external_reference_id` từ cổng thanh toán |
| 2 | `payment-service` gọi mock external bank API (giả lập luôn trả về thành công) |
| 3 | Giao dịch nạp tiền lưu vào `transactions` với `type='TOP_UP'`, `status='PROCESSING'`, `saga_state='CREDIT_PENDING'`, `external_reference_id` lưu lại để đối soát sau này |
| 4 | Ghi `outbox_events` (`CreditCommand`), publish sang `wallet.commands` |
| 5 | `wallet-service` consume `CreditCommand`, check idempotency key `topup:{external_reference_id}`, tra cứu ví user, cộng thêm $1,000, publish `CreditCompleted` |
| 6 | `payment-service` consume `CreditCompleted` → cập nhật `status='COMPLETED'`, `saga_state='COMPLETED'` |
| 7 | Ghi vào `wallet_transaction_logs`: `balance_before=$0`, `balance_after=$1,000` |

> **Lưu ý quan trọng:** `status` KHÔNG được set `COMPLETED` ở bước 3 — giao dịch chỉ thực sự hoàn tất sau khi `wallet-service` xác nhận cộng tiền thành công (bước 6). Đặt `COMPLETED` sớm sẽ tạo ra khoảng thời gian transaction "nói dối" là đã xong trong khi tiền chưa thực sự vào ví — mâu thuẫn với mô hình Saga đang dùng ở UC3.

### 4. Ngoại lệ (Sad Path) — bổ sung

**4.1 — Webhook resend từ cổng thanh toán**
Cổng thanh toán bên ngoài có thể gửi lại webhook nếu không nhận được ACK kịp thời (network timeout, service chậm phản hồi).

`wallet-service` dùng key idempotency riêng cho top-up:
```
"topup:{external_reference_id}"
```
Nếu key đã tồn tại trong `idempotency_keys` → bỏ qua, trả về cached result, **không cộng tiền lần 2**.

**4.2 — Ví đang FROZEN**
`wallet-service` từ chối `CreditCommand`, publish `CreditFailed`. `payment-service` consume `CreditFailed` → cập nhật `transactions.status = 'FAILED'`, `saga_state='FAILED'`, `failure_reason = 'Wallet is frozen'`. Vì `status` chưa từng bị set `COMPLETED` trước đó (xem lưu ý ở mục 3), transition sang `FAILED` là nhất quán, không có giai đoạn "nói dối".

---

## Use Case 3: Chuyển tiền nội bộ (P2P Transfer) — Khó nhất

### 1. Mục tiêu
User A (người gửi) chuyển tiền cho User B (người nhận) ngay trong hệ thống, không tốn phí.

### 2. Quy tắc nghiệp vụ khắt khe

**2.1 — Ràng buộc số dư**
Số dư của A phải ≥ số tiền muốn chuyển. Không bao giờ được phép âm ví (`wallets.balance >= 0` — CHECK constraint ở tầng DB làm lớp chặn cuối cùng).

**2.2 — Ràng buộc trạng thái**
Cả ví A và ví B đều phải đang `ACTIVE`. Nếu B bị khóa tài khoản, tiền của A phải được giữ nguyên hoặc hoàn trả — **không bao giờ được để tiền "biến mất" khỏi hệ thống**.

**2.3 — Tính lũy đẳng (Idempotency) — cần phân biệt 2 tầng**

| Tầng | Tình huống | Cơ chế |
|---|---|---|
| **API (HTTP)** | User bấm nút "Chuyển" 2 lần do mạng chập chờn → 2 request `POST /transfer` gửi tới `payment-service` | Client gửi kèm header `Idempotency-Key` (UUID sinh ở client). `payment-service` check trong bảng `idempotency_keys` (thêm mới ở `payment_db`) trước khi tạo `transaction` record mới. Nếu key đã tồn tại → trả về kết quả giao dịch cũ, không tạo transaction thứ 2 |
| **Kafka Consumer** | `DebitCommand` bị Kafka re-deliver do consumer crash sau khi xử lý nhưng trước khi commit offset (at-least-once delivery) | Redis SETNX với key `debit:{transactionId}:{userId}` / `credit:{transactionId}:{userId}` (đã thiết kế ở `wallet_db.idempotency_keys`) |

> Hai cơ chế này độc lập và bổ trợ nhau — API-level chặn duplicate request từ client, Kafka-level chặn duplicate event trong lúc xử lý bất đồng bộ.

**2.4 — Không tự chuyển cho chính mình**
`payment-service` chặn ngay ở tầng API (validate trước khi tạo `transaction`): nếu `sender_id == receiver_id` → trả về HTTP 400, không cho phép Saga khởi động. Nếu không chặn, Saga sẽ debit rồi credit cùng 1 ví — vừa vô nghĩa vừa dễ lộ race condition khi 2 lệnh cùng update 1 row với `@Version`.

**2.5 — Currency phải khớp giữa 2 ví (hệ quả của multi-currency wallet)**
Vì `wallets` hỗ trợ multi-currency (`UNIQUE(user_id, currency)`), request transfer bắt buộc chỉ định rõ `currency` (ví dụ A có cả USDT lẫn BTC, cần biết chuyển loại nào).

Trường hợp ví B chưa từng giữ currency đó (ví dụ B chưa bao giờ có ví USDT):
- `wallet-service` xử lý `CreditCommand`: nếu không tìm thấy `wallets` row với `(receiver_id, currency)` → **tự động tạo ví mới** `balance=0` cho currency đó trước khi cộng tiền, thay vì coi là `CreditFailed`
- **Xử lý race khi auto-create (insert-or-get):** nếu 2 `CreditCommand` cùng `(receiver_id, currency)` tới gần như đồng thời, cả hai cùng thử INSERT ví mới → `UNIQUE(user_id, currency)` sẽ chặn cái thứ 2 bằng unique-violation. Implementation phải **catch lỗi này → re-read ví vừa được tạo rồi cộng tiền bình thường**, tuyệt đối không để nó biến thành `CreditFailed` oan. Đây là pattern "insert-or-get" chuẩn
- Đây là hành vi hợp lý về nghiệp vụ (giống Binance Pay: nhận coin lần đầu tự động mở ví coin đó), tránh giao dịch fail oan chỉ vì B chưa từng đụng tới currency này

### 3. Luồng chính (Happy Path — A chuyển cho B $500 thành công)

| Bước | Hành động |
|---|---|
| 1 — Tạo lệnh | User A tạo lệnh chuyển $500 USDT cho B. `payment-service` check `Idempotency-Key`, validate `sender_id != receiver_id`, tạo `transaction` với `status='INITIATED'`, `saga_state='DEBIT_PENDING'` |
| 2 — Trừ tiền A | `payment-service` ghi `outbox_events` (`DebitCommand`) → publish → `wallet-service` check idempotency, check `balance >= amount`, trừ tiền với Optimistic Locking (`@Version`) → publish `DebitCompleted` |
| 3 — Ghi nhận Debit xong | `payment-service` consume `DebitCompleted` → cập nhật `saga_state='DEBIT_COMPLETED'` (state trung gian — xem giải thích ở mục "Saga State Machine" bên dưới) |
| 4 — Phát lệnh Credit | `payment-service` ghi `outbox_events` (`CreditCommand`) → cập nhật `saga_state='CREDIT_PENDING'` → publish → `wallet-service` check idempotency, tìm hoặc tự tạo ví B đúng currency, cộng tiền → publish `CreditCompleted` |
| 5 — Hoàn tất | `payment-service` consume `CreditCompleted` → `status='COMPLETED'`, `saga_state='COMPLETED'`. Ghi 2 rows vào `transaction_history` (CQRS — 1 cho A loại `SENT`, 1 cho B loại `RECEIVED`). `notification-service` gửi thông báo biến động số dư cho cả A và B |

**Saga State Machine — vì sao cần `DEBIT_COMPLETED` làm state trung gian:**
Nếu `payment-service` crash đúng lúc giữa "nhận được `DebitCompleted`" và "ghi xong `outbox_events` cho `CreditCommand`", thì:
- Không có `DEBIT_COMPLETED`: khi service phục hồi, không biết chính xác đã nhận `DebitCompleted` chưa → có nguy cơ xử lý sai (bỏ sót hoặc lặp)
- Có `DEBIT_COMPLETED`: service phục hồi, query `transactions WHERE saga_state='DEBIT_COMPLETED'` → biết chính xác cần resume từ bước "phát lệnh Credit", không cần đoán

Đây là lý do dùng đủ 3 state `DEBIT_PENDING → DEBIT_COMPLETED → CREDIT_PENDING` thay vì gộp tắt `DEBIT_PENDING → CREDIT_PENDING`.

**Optimization (không bắt buộc):** ở Bước 1, `payment-service` có thể check nhanh trạng thái ví B (REST call đồng bộ hoặc cached status) trước khi khởi động Saga — nếu B đã `CLOSED` từ trước thì từ chối ngay, tiết kiệm 1 vòng Saga không cần thiết. Tuy nhiên đây **chỉ là tối ưu giảm xác suất**, không thay thế được Compensating Transaction ở mục 5 bên dưới — vì B hoàn toàn có thể bị khóa **giữa lúc** Saga đang chạy (race condition giữa bước 1 và bước 4).

### 4. Luồng ngoại lệ 1 — A không đủ tiền (Fail Fast)

| Bước | Hành động |
|---|---|
| 1 | `payment-service` ra lệnh `DebitCommand` |
| 2 | `wallet-service` thấy ví A chỉ có $100 → từ chối trừ tiền → publish `DebitFailed` |
| 3 | `payment-service` consume `DebitFailed` → `status='FAILED'`, `saga_state='FAILED'`, `failure_reason='Insufficient balance'` |
| 4 | Báo cho A: "Số dư không đủ" |

Vì chưa trừ được tiền A, **không cần Compensating Transaction** — đây là lý do `saga_state` có 2 nhánh kết thúc khác nhau: `FAILED` (chưa trừ tiền) vs `REVERSED` (đã trừ, phải hoàn).

### 5. Luồng ngoại lệ 2 — Lỗi bán phần: Tiền đã rời ví A nhưng ví B bị khóa/lỗi DB (quan trọng nhất)

**Tình huống:** Lệnh trừ tiền A ($500) đã thành công. Nhưng khi ra lệnh cộng tiền cho B, hệ thống phát hiện B đã bị khóa tài khoản (hoặc DB của B bị sập).

**Nguyên tắc xử lý:** Hệ thống **không được** để giao dịch ở trạng thái treo — làm mất tiền của A là điều tuyệt đối không được phép trong hệ thống tài chính.

| Bước | Hành động |
|---|---|
| 1 | `wallet-service` xử lý `CreditCommand` cho B → thất bại (B bị `FROZEN`) → publish `CreditFailed` |
| 2 | `payment-service` consume `CreditFailed` → `saga_state='COMPENSATING'` |
| 3 | `payment-service` ghi `outbox_events` (`DebitReverseCommand`) — **Compensating Transaction** |
| 4 | `wallet-service` consume `DebitReverseCommand`, hoàn $500 lại cho ví A → publish `DebitReversed` |
| 5 | `payment-service` consume `DebitReversed` → `status='REVERSED'`, `saga_state='REVERSED'` |
| 6 | User A nhận thông báo: "Giao dịch thất bại do tài khoản người nhận có vấn đề. Tiền đã được hoàn lại vào ví của bạn" |

**Đây là bản chất thật sự của Saga Pattern** — không có transaction ACID xuyên suốt nhiều service như trong 1 database, mà dùng chuỗi Compensating Transaction để đưa hệ thống về trạng thái nhất quán khi có lỗi giữa chừng.

### 6. Edge case nâng cao — Compensation thất bại (bổ sung)

**Tình huống:** Bước 4 ở trên (`DebitReverseCommand`) cũng thất bại — ví dụ ví A đã bị đóng ngay trong lúc Saga đang chạy, hoặc DB của `wallet-service` bị sập đúng lúc xử lý reverse.

**Đây là câu hỏi cấp Senior:** hệ thống không được phép để tiền "kẹt lại" vĩnh viễn — vừa đã trừ A, vừa không cộng được B, vừa không hoàn được A.

**Nguyên tắc xử lý:**
- `DebitReverseCommand` phải được thiết kế **idempotent và retriable** — `wallet-service` có thể nhận lại lệnh này nhiều lần mà không gây lỗi (dùng cùng idempotency key `debit-reverse:{transactionId}:{userId}`)
- `payment-service` retry `DebitReverseCommand` với backoff (ví dụ 3 lần, giãn cách 5s/15s/60s)
- Sau N lần retry vẫn thất bại → **không được tự ý đánh dấu giao dịch là xong**. Chuyển `saga_state='COMPENSATION_FAILED'`, đẩy event vào Dead Letter Queue (Kafka topic riêng `wallet.dlq`), và bắn alert cho vận hành (human intervention)
- **`status` giữ nguyên `PROCESSING`** khi `saga_state='COMPENSATION_FAILED'` (KHÔNG set `FAILED`/`REVERSED`). Lý do: giao dịch thực sự **chưa** kết thúc — tiền đã trừ A nhưng chưa hoàn được. Để `status='PROCESSING'` giúp nó vẫn hiện là "đang treo, cần can thiệp" trên dashboard vận hành, đúng bản chất sổ sách
- Giao dịch ở trạng thái này phải hiển thị rõ ràng trên dashboard vận hành — đây là lý do tiền trong hệ thống Fintech không bao giờ tự "biến mất" khỏi sổ sách, kể cả khi automation thất bại hoàn toàn

> Trong phỏng vấn, đây là điểm phân biệt rõ nhất Mid-level và Senior: Mid-level dừng lại ở "rollback nếu lỗi", Senior phải trả lời được "nếu rollback cũng lỗi thì sao".

---

## Tổng hợp thay đổi schema cần thiết

### 1. Thêm bảng mới vào `payment_db`

```sql
-- ------------------------------------------------------------
-- payment_idempotency_keys
-- API-level Idempotency — khác với wallet_db.idempotency_keys
-- (Kafka-consumer-level). Chặn duplicate request ngay tại API,
-- trước khi Saga bắt đầu chạy.
--
-- Key = Idempotency-Key header do client sinh (UUID), gửi kèm
-- mỗi request POST /api/payments/transfer
-- ------------------------------------------------------------
CREATE TABLE payment_idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    transaction_id  UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    result          JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP    NOT NULL DEFAULT NOW() + INTERVAL '24 hours',

    CONSTRAINT payment_idempotency_status_chk CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_payment_idempotency_expires ON payment_idempotency_keys (expires_at);
```

### 2. Thêm cột vào `transactions`

```sql
-- external_reference_id: chỉ dùng cho type=TOP_UP, lưu ID từ cổng thanh toán
-- để đối soát (reconciliation) sau này, và làm idempotency key
-- "topup:{external_reference_id}" ở wallet-service
ALTER TABLE transactions ADD COLUMN external_reference_id VARCHAR(255);
CREATE INDEX idx_transactions_external_ref ON transactions (external_reference_id);
```

### 3. Quyết định giữ nguyên `saga_state = 'DEBIT_COMPLETED'`

State này **được sử dụng thật** (không phải state mồ côi) — làm điểm resume chính xác khi `payment-service` crash giữa lúc nhận `DebitCompleted` và lúc phát `CreditCommand`. Xem giải thích chi tiết ở UC3 mục 3.

### 4. Business rule bổ sung cho `wallet-service`

- Khi xử lý `CreditCommand`: nếu `wallets` chưa có row `(receiver_id, currency)` → tự tạo ví mới `balance=0` trước khi cộng tiền (không coi là lỗi)
- `DebitReverseCommand` phải dùng idempotency key riêng: `debit-reverse:{transactionId}:{userId}`, và phải retriable

### 5. Business rule bổ sung cho `payment-service`

- Validate `sender_id != receiver_id` ngay khi nhận request, trước khi tạo `transaction`
- Validate `currency` bắt buộc có trong request transfer
- Thêm `saga_state = 'COMPENSATION_FAILED'` vào CHECK constraint — dùng khi `DebitReverseCommand` retry hết số lần vẫn thất bại, cần human intervention

---

## Checklist trước khi giao Kiro

- [ ] UC1: Outbox Pattern đảm bảo `UserRegistered` không mất khi `wallet-service` down
- [ ] UC2: `status` KHÔNG set `COMPLETED` sớm — chỉ set sau khi nhận `CreditCompleted`
- [ ] UC2: `external_reference_id` lưu vào `transactions`, dùng làm idempotency key `topup:{external_reference_id}`
- [ ] UC3: Idempotency-Key header ở tầng API (bảng `payment_idempotency_keys` mới)
- [ ] UC3: Idempotency Redis SETNX ở tầng Kafka consumer (`debit:`/`credit:` — đã có)
- [ ] UC3: Chặn `sender_id == receiver_id` ngay tại API, trả HTTP 400
- [ ] UC3: Currency bắt buộc trong request; `wallet-service` tự tạo ví B nếu chưa có currency đó
- [ ] UC3: Dùng đủ 3 saga_state `DEBIT_PENDING → DEBIT_COMPLETED → CREDIT_PENDING` để resume đúng vị trí khi crash
- [ ] UC3: Compensating Transaction đầy đủ 6 bước khi `CreditFailed`
- [ ] UC3: `DebitReverseCommand` idempotent + retriable; retry hết vẫn fail → `COMPENSATION_FAILED` + Dead Letter Queue (`wallet.dlq`) + alert; `status` giữ nguyên `PROCESSING`
- [ ] UC3: Auto-create ví B theo pattern insert-or-get (catch unique-violation → re-read → credit), không để thành `CreditFailed` oan
- [ ] Tất cả sad path đều có `failure_reason` ghi rõ nguyên nhân cho user
