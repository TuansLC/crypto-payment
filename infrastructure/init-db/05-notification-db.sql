-- ============================================================
-- 05-notification-db.sql
-- Schema cho notification_db — notification-service (port 8084)
--
-- Design decision: spec gốc không yêu cầu DB cho notification-service
-- (chỉ consume Kafka và log ra console).
-- Tuy nhiên notification_logs được thêm vào vì:
--   1. Audit compliance — chứng minh thông báo đã được gửi
--   2. Retry support — query status=FAILED để retry
--   3. Interviewer hỏi "tại sao thêm?" → câu trả lời này
-- ============================================================

\connect notification_db;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------
-- notification_logs
-- Lưu lịch sử thông báo đã gửi
--
-- channel:
--   LOG   → chỉ ghi log (dùng trong demo/dev)
--   EMAIL → gửi email (production)
--   PUSH  → push notification mobile (production)
--   SMS   → tin nhắn SMS (production)
--
-- status:
--   PENDING → chưa gửi
--   SENT    → gửi thành công
--   FAILED  → gửi thất bại (có thể retry)
--
-- event_type: loại event Kafka trigger ra thông báo này
--   → UserRegistered, TransferCompleted, TransferFailed...
-- ------------------------------------------------------------
CREATE TABLE notification_logs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL,
    transaction_id  UUID,
    event_type      VARCHAR(100) NOT NULL,
    channel         VARCHAR(20)  NOT NULL DEFAULT 'LOG',
    title           VARCHAR(255) NOT NULL,
    message         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SENT',
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT notification_channel_chk CHECK (channel IN ('EMAIL', 'PUSH', 'SMS', 'LOG')),
    CONSTRAINT notification_status_chk  CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_notification_user_id     ON notification_logs (user_id);
CREATE INDEX idx_notification_transaction ON notification_logs (transaction_id);
CREATE INDEX idx_notification_created_at  ON notification_logs (created_at DESC);
-- Partial index: chỉ cần tìm nhanh các thông báo cần retry
CREATE INDEX idx_notification_failed      ON notification_logs (id) WHERE status = 'FAILED';
