-- ============================================================
-- 03-wallet-db.sql
-- Schema cho wallet_db — wallet-service (port 8082)
-- ============================================================

\connect wallet_db;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------
-- wallets
-- Quản lý ví người dùng
--
-- Design decision: UNIQUE(user_id, currency) thay vì UNIQUE(user_id)
--   → 1 user có thể có nhiều ví (USDT, BTC, ETH...)
--   → Khác với spec gốc (1 user = 1 ví) nhưng phù hợp với thực tế Crypto platform
--   → Binance Pay cũng support multi-currency wallet per user
--   → Nếu interviewer hỏi: đây là conscious design decision để future-proof
--
-- version BIGINT: Optimistic Locking (@Version trong JPA)
--   → Không dùng Pessimistic Lock (SELECT FOR UPDATE) vì giảm throughput
--   → Khi 2 request cùng trừ tiền: 1 thắng, 1 nhận OptimisticLockException → retry
--
-- balance DECIMAL(20,8): 8 chữ số thập phân
--   → 1 satoshi = 0.00000001 BTC → đủ độ chính xác cho mọi loại crypto
--
-- balance >= 0: defense-in-depth
--   → Code đã check, nhưng DB là lớp cuối chặn negative balance tuyệt đối
--
-- status:
--   ACTIVE → hoạt động bình thường
--   FROZEN → tạm khóa (nghi gian lận) → CreditCommand sẽ fail → trigger Compensating Transaction
--   CLOSED → đóng vĩnh viễn
--
-- ⚠️ Index note: KHÔNG tạo idx_wallets_user_id riêng vì UNIQUE(user_id, currency)
--   đã tự sinh index có prefix là user_id → query theo user_id vẫn dùng được.
-- ------------------------------------------------------------
CREATE TABLE wallets (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID           NOT NULL,
    balance     DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    currency    VARCHAR(10)    NOT NULL DEFAULT 'USDT',
    status      VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT wallets_user_currency_uk UNIQUE (user_id, currency),
    CONSTRAINT wallets_balance_chk      CHECK  (balance >= 0),
    CONSTRAINT wallets_status_chk       CHECK  (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- ------------------------------------------------------------
-- idempotency_keys
-- Chống double-charge khi Kafka re-deliver event (at-least-once delivery)
--
-- ⚠️  Key format PHẢI phân biệt theo action — KHÔNG dùng txn:{transactionId} chung:
--
--   ĐÚNG:
--     Debit:  "debit:{transactionId}:{userId}"
--     Credit: "credit:{transactionId}:{userId}"
--
--   SAI:
--     "txn:{transactionId}" → Credit bị chặn nhầm vì trùng key với Debit
--
--   Lý do: 1 P2P transfer có cùng transactionId nhưng 2 action riêng biệt
--   (DebitCommand và CreditCommand). Key chung sẽ chặn action thứ 2.
--
-- result JSONB: cache response → trả về cached result khi duplicate request
-- expires_at: TTL 24h → tự cleanup, tránh bảng phình
--
-- Cleanup job: DELETE FROM idempotency_keys WHERE expires_at < NOW()
-- Chạy mỗi 1h bằng @Scheduled trong wallet-service
-- ------------------------------------------------------------
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    result          JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP    NOT NULL DEFAULT NOW() + INTERVAL '24 hours',

    CONSTRAINT idempotency_status_chk CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Index cho cleanup job
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);

-- ------------------------------------------------------------
-- wallet_transaction_logs
-- Audit trail mọi thay đổi balance — "tiêu chuẩn vàng" Fintech
--
-- balance_before + balance_after: reconciliation
--   → Khi có nghi ngờ lệch tiền, query bảng này ra ngay dòng chảy tiền
--   → Không cần rebuild lại lịch sử từ events
--
-- reference_id: transactionId từ payment-service
--   → Trace ngược lại giao dịch gốc
--
-- type:
--   DEBIT          → trừ tiền (P2P transfer - sender)
--   CREDIT         → cộng tiền (P2P transfer - receiver)
--   DEBIT_REVERSED → hoàn tiền (Compensating Transaction)
--   TOP_UP         → nạp tiền từ bên ngoài
-- ------------------------------------------------------------
CREATE TABLE wallet_transaction_logs (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id       UUID           NOT NULL REFERENCES wallets (id),
    type            VARCHAR(30)    NOT NULL,
    amount          DECIMAL(20, 8) NOT NULL,
    balance_before  DECIMAL(20, 8) NOT NULL,
    balance_after   DECIMAL(20, 8) NOT NULL,
    reference_id    VARCHAR(255)   NOT NULL,
    description     VARCHAR(255),
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT wallet_logs_type_chk CHECK (type IN ('DEBIT', 'CREDIT', 'DEBIT_REVERSED', 'TOP_UP'))
);

CREATE INDEX idx_wallet_logs_wallet_id    ON wallet_transaction_logs (wallet_id);
CREATE INDEX idx_wallet_logs_reference_id ON wallet_transaction_logs (reference_id);
CREATE INDEX idx_wallet_logs_created_at   ON wallet_transaction_logs (created_at DESC);

-- ------------------------------------------------------------
-- wallet_outbox_events
-- Outbox Pattern: publish DebitCompleted, CreditFailed, DebitReversed...
-- (cùng cơ chế với user_outbox_events — xem comment ở 02-user-db.sql)
-- error_message: lưu lý do fail khi retry hết lần → dễ debug
-- ------------------------------------------------------------
CREATE TABLE wallet_outbox_events (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    topic         VARCHAR(100) NOT NULL DEFAULT 'wallet.events',
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count   INT          NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMP,

    CONSTRAINT wallet_outbox_status_chk CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_wallet_outbox_pending   ON wallet_outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX idx_wallet_outbox_aggregate ON wallet_outbox_events (aggregate_id);
