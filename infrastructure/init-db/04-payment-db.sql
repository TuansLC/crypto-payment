-- ============================================================
-- 04-payment-db.sql
-- Schema cho payment_db — payment-service (port 8083)
-- Saga Orchestrator: điều phối toàn bộ luồng P2P Transfer
-- ============================================================

\connect payment_db;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------
-- transactions
-- Bảng chính — Write model của CQRS
-- payment-service là Saga Orchestrator, bảng này là State Machine
--
-- type:
--   TRANSFER → P2P transfer giữa 2 user
--   TOP_UP   → nạp tiền từ bên ngoài (không có sender)
--
-- sender_id: nullable vì TOP_UP không có sender
--   → Dùng conditional constraint thay vì NOT NULL cứng:
--   → TRANSFER: bắt buộc cả sender_id VÀ receiver_id
--   → TOP_UP:   chỉ cần receiver_id (người được nạp tiền)
--
-- status:
--   INITIATED  → vừa tạo, chưa xử lý
--   PROCESSING → Saga đang chạy
--   COMPLETED  → Saga hoàn tất thành công
--   FAILED     → Saga thất bại (DebitFailed — A không đủ tiền)
--   REVERSED   → Đã hoàn tiền (CreditFailed → Compensating Transaction)
--
-- saga_state (State Machine):
--   DEBIT_PENDING   → gửi DebitCommand, chờ wallet-service phản hồi
--   DEBIT_COMPLETED → DebitCompleted nhận được, gửi CreditCommand
--   CREDIT_PENDING  → chờ CreditCompleted hoặc CreditFailed
--   COMPLETED       → CreditCompleted nhận được → Saga done
--   COMPENSATING    → CreditFailed → gửi DebitReverseCommand
--   REVERSED        → DebitReversed nhận được → Saga rollback done
--   FAILED          → DebitFailed → không cần rollback (chưa trừ tiền)
--
-- failure_reason: lưu nguyên nhân fail → hiển thị cho user, debug
-- ------------------------------------------------------------
CREATE TABLE transactions (
                              id                     UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                              sender_id              UUID,
                              receiver_id            UUID           NOT NULL,
                              amount                 DECIMAL(20, 8) NOT NULL,
                              currency               VARCHAR(10)    NOT NULL DEFAULT 'USDT',
                              type                   VARCHAR(20)    NOT NULL DEFAULT 'TRANSFER',
                              status                 VARCHAR(20)    NOT NULL DEFAULT 'INITIATED',
                              saga_state             VARCHAR(30)    NOT NULL DEFAULT 'DEBIT_PENDING',
                              failure_reason         VARCHAR(255),
    -- Chỉ dùng cho type=TOP_UP: ID giao dịch từ cổng thanh toán bên ngoài,
    -- dùng để đối soát (reconciliation) và làm idempotency key "topup:{external_reference_id}"
                              external_reference_id  VARCHAR(255),
                              created_at             TIMESTAMP      NOT NULL DEFAULT NOW(),
                              updated_at             TIMESTAMP      NOT NULL DEFAULT NOW(),

                              CONSTRAINT transactions_amount_chk     CHECK (amount > 0),
                              CONSTRAINT transactions_type_chk       CHECK (type IN ('TRANSFER', 'TOP_UP')),
                              CONSTRAINT transactions_status_chk     CHECK (status IN (
                                                                                       'INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED'
                                  )),
    -- DEBIT_COMPLETED: state trung gian thật sự dùng — đánh dấu "đã nhận DebitCompleted,
    -- chưa kịp ghi CreditCommand". Giúp payment-service resume đúng vị trí nếu crash
    -- giữa 2 bước này, thay vì phải đoán lại từ đầu.
    -- COMPENSATION_FAILED: DebitReverseCommand retry hết số lần vẫn thất bại,
    -- cần Dead Letter Queue + human intervention (xem UC3 mục 6)
                              CONSTRAINT transactions_saga_state_chk CHECK (saga_state IN (
                                                                                           'DEBIT_PENDING', 'DEBIT_COMPLETED', 'CREDIT_PENDING',
                                                                                           'COMPLETED', 'COMPENSATING', 'REVERSED', 'FAILED', 'COMPENSATION_FAILED'
                                  )),
    -- sender_id nullable vì TOP_UP không có sender (nạp từ bên ngoài)
    -- TRANSFER bắt buộc phải có cả sender_id và receiver_id
                              CONSTRAINT transactions_transfer_chk CHECK (
                                  type != 'TRANSFER' OR (sender_id IS NOT NULL AND receiver_id IS NOT NULL)
),
    -- Chặn tự chuyển cho chính mình ngay ở tầng DB — lớp phòng thủ cuối
    -- (dù đã validate ở API layer theo UC3 mục 2.4)
    CONSTRAINT transactions_no_self_transfer_chk CHECK (
        sender_id IS NULL OR sender_id != receiver_id
    )
);

CREATE INDEX idx_transactions_sender_id     ON transactions (sender_id);
CREATE INDEX idx_transactions_receiver_id   ON transactions (receiver_id);
CREATE INDEX idx_transactions_status        ON transactions (status);
CREATE INDEX idx_transactions_saga_state    ON transactions (saga_state);
CREATE INDEX idx_transactions_created_at    ON transactions (created_at DESC);
-- Partial index: external_reference_id chỉ có giá trị với TOP_UP, mọi TRANSFER đều NULL
-- → index đầy đủ sẽ toàn NULL vô ích. Partial index chỉ giữ hàng TOP_UP.
CREATE INDEX idx_transactions_external_ref  ON transactions (external_reference_id)
    WHERE external_reference_id IS NOT NULL;

-- ------------------------------------------------------------
-- payment_idempotency_keys
-- API-level Idempotency — KHÁC với wallet_db.idempotency_keys
-- (bên đó là Kafka-consumer-level, chống duplicate DebitCommand/CreditCommand)
--
-- Bảng này chặn duplicate HTTP request ngay tại tầng API, trước khi
-- Saga bắt đầu chạy. Tình huống: user bấm nút "Chuyển" 2 lần do mạng
-- chập chờn → 2 request POST /api/payments/transfer cùng lúc.
--
-- Key = Idempotency-Key header do client sinh (UUID), gửi kèm mỗi request.
-- Nếu key đã tồn tại → trả về kết quả transaction cũ, không tạo mới.
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
CREATE INDEX idx_payment_idempotency_txn     ON payment_idempotency_keys (transaction_id);

-- ------------------------------------------------------------
-- outbox_events
-- Outbox Pattern cho payment-service — QUAN TRỌNG NHẤT
--
-- payment-service là Saga Orchestrator: thứ tự publish phải tuyệt đối đúng
--   DebitCommand → (chờ reply) → CreditCommand → (chờ reply) → DebitReverseCommand
--   Publish sai thứ tự = phá vỡ toàn bộ State Machine của Saga
--
-- id BIGSERIAL: đảm bảo FIFO tuyệt đối (UUID random không đảm bảo được)
-- event_id UUID: external reference, không dùng để order
-- error_message: lưu exception khi retry fail → debug production issue
--
-- Composite index (status, id):
--   SELECT * FROM outbox_events WHERE status='PENDING' ORDER BY id LIMIT 100
--   → 1 index scan, vừa nhanh vừa đúng thứ tự
--
-- Distributed Lock cần thiết khi chạy nhiều instance:
--   Redis SETNX "lock:outbox-publisher" TTL 30s
--   → chỉ 1 instance publish tại một thời điểm
--   → tránh duplicate publish dù đã có Idempotency
-- ------------------------------------------------------------
CREATE TABLE outbox_events (
                               id            BIGSERIAL    PRIMARY KEY,
                               event_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
                               aggregate_id  UUID         NOT NULL,
                               event_type    VARCHAR(100) NOT NULL,
                               topic         VARCHAR(100) NOT NULL,
                               payload       JSONB        NOT NULL,
                               status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                               retry_count   INT          NOT NULL DEFAULT 0,
                               error_message TEXT,
                               created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
                               published_at  TIMESTAMP,

                               CONSTRAINT outbox_status_chk CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_pending   ON outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);
CREATE INDEX idx_outbox_topic     ON outbox_events (topic);

-- ------------------------------------------------------------
-- transaction_history
-- CQRS Read Model — tách hoàn toàn khỏi write model (transactions)
--
-- Mỗi P2P transfer tạo 2 rows:
--   row 1: user_id=senderId,   type=SENT,     amount âm  (-500 USDT)
--   row 2: user_id=receiverId, type=RECEIVED, amount dương (+500 USDT)
--
-- counterpart_id: đối tác giao dịch
--   → SENT row: counterpart_id = receiver
--   → RECEIVED row: counterpart_id = sender
--   → Hiển thị "Đã chuyển cho [counterpart]" hoặc "Nhận từ [counterpart]"
--
-- Query API lịch sử: SELECT * FROM transaction_history
--   WHERE user_id = ? ORDER BY created_at DESC
--   → Không cần JOIN, không cần OR, chỉ 1 index scan → siêu nhanh
--
-- Eventual consistency: read model có thể lag vài giây so với write model
--   → Chấp nhận được cho lịch sử giao dịch (không cần real-time)
--
-- Partitioning (production consideration):
--   PARTITION BY RANGE (created_at) — monthly partitions
--   → Khi data lớn (hàng triệu giao dịch), query chỉ scan đúng partition
--   → Interviewer hỏi "data lớn thì sao?" → đây là câu trả lời
--   → Không implement trong demo (over-engineering), nhưng phải biết nói
-- ------------------------------------------------------------
CREATE TABLE transaction_history (
                                     id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                                     transaction_id  UUID           NOT NULL,
                                     user_id         UUID           NOT NULL,
                                     counterpart_id  UUID,
                                     type            VARCHAR(20)    NOT NULL,
                                     amount          DECIMAL(20, 8) NOT NULL,
                                     currency        VARCHAR(10)    NOT NULL DEFAULT 'USDT',
                                     status          VARCHAR(20)    NOT NULL,
                                     description     VARCHAR(255),
                                     created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),

                                     CONSTRAINT tx_history_type_chk CHECK (type IN ('SENT', 'RECEIVED', 'TOP_UP'))
);

-- Composite index (user_id, created_at DESC): tối ưu cho API lịch sử theo user
CREATE INDEX idx_tx_history_user_created   ON transaction_history (user_id, created_at DESC);
CREATE INDEX idx_tx_history_transaction_id ON transaction_history (transaction_id);

-- ------------------------------------------------------------
-- saga_logs
-- Debug trail: ghi lại mọi state transition của Saga
--
-- Mỗi khi payment-service nhận Kafka event và chuyển saga_state,
-- ghi 1 row vào đây: from_state → to_state vì event nào
--
-- Khi hệ thống có vấn đề:
--   SELECT * FROM saga_logs WHERE transaction_id = ? ORDER BY created_at
--   → Thấy ngay Saga kẹt ở state nào, event nào trigger
--
-- Câu hỏi phỏng vấn: "Làm sao trace bug trong distributed transaction?"
--   → "Em có bảng saga_logs ghi lại toàn bộ state transition,
--      kết hợp với Kafka message offset để replay lại chính xác
--      chuỗi sự kiện đã xảy ra."
--
-- Retention policy (production):
--   DELETE FROM saga_logs WHERE created_at < NOW() - INTERVAL '90 days'
--   Chạy bằng pg_cron hoặc @Scheduled job hàng tuần
--   → Tránh bảng phình, 90 ngày đủ để audit và debug
-- ------------------------------------------------------------
CREATE TABLE saga_logs (
                           id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                           transaction_id  UUID         NOT NULL,
                           from_state      VARCHAR(30),
                           to_state        VARCHAR(30)  NOT NULL,
                           event_type      VARCHAR(100) NOT NULL,
                           event_payload   JSONB,
                           created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_saga_logs_transaction_id ON saga_logs (transaction_id);
CREATE INDEX idx_saga_logs_created_at     ON saga_logs (created_at DESC);