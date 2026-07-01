-- ============================================================
-- 02-user-db.sql
-- Schema cho user_db — user-service (port 8081)
-- ============================================================

\connect user_db;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------
-- users
-- Bảng chính quản lý tài khoản người dùng
--
-- status:
--   ACTIVE    → tài khoản hoạt động bình thường
--   SUSPENDED → tạm khóa (nghi gian lận, vi phạm)
--   DELETED   → soft delete theo GDPR
--
-- deleted_at: set khi status=DELETED
--   → Cleanup job chạy mỗi ngày: DELETE WHERE deleted_at < NOW() - INTERVAL '30 days'
--   → Đáp ứng GDPR Right to Erasure (xóa dữ liệu sau 30 ngày)
--   → updated_at được xử lý bởi JPA @PreUpdate, không dùng trigger để tránh duplicate logic
--
-- ⚠️ Index note: KHÔNG tạo index riêng cho email/username vì UNIQUE constraint
--   (users_email_uk, users_username_uk) đã tự sinh index — tạo thêm là dư thừa.
-- ------------------------------------------------------------
CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP,

    CONSTRAINT users_username_uk UNIQUE (username),
    CONSTRAINT users_email_uk    UNIQUE (email),
    CONSTRAINT users_status_chk  CHECK  (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

-- Partial index: chỉ index user chưa bị xóa — tối ưu query thông thường
CREATE INDEX idx_users_active ON users (id) WHERE deleted_at IS NULL;

-- ------------------------------------------------------------
-- user_outbox_events
-- Outbox Pattern: ghi event cùng transaction với INSERT users
--
-- id BIGSERIAL (không dùng UUID):
--   UUID v4 random, không có thứ tự thời gian → 2 event cùng mili-giây
--   có thể publish sai thứ tự lên Kafka.
--   BIGSERIAL tự tăng tuyệt đối → đảm bảo FIFO.
--
-- event_id UUID: giữ riêng làm external reference (trả về client, trace log)
-- error_message: lưu lý do fail khi retry_count maxed out → dễ debug
--
-- Partial index idx_user_outbox_pending: chỉ index hàng PENDING.
--   99% hàng sẽ thành PUBLISHED → không cần index. Partial index giữ index
--   nhỏ tí, cronjob "WHERE status='PENDING' ORDER BY id" scan cực nhanh
--   và không phình theo lịch sử.
-- ------------------------------------------------------------
CREATE TABLE user_outbox_events (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    topic         VARCHAR(100) NOT NULL DEFAULT 'user.events',
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count   INT          NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMP,

    CONSTRAINT user_outbox_status_chk CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_user_outbox_pending   ON user_outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX idx_user_outbox_aggregate ON user_outbox_events (aggregate_id);
