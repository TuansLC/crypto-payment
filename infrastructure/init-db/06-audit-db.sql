-- ============================================================
-- 06-audit-db.sql
-- Schema cho audit_db — audit-service (port 8085)
--
-- audit-service consume TẤT CẢ Kafka topic (user.events, wallet.commands,
-- wallet.events, payment.events, wallet.dlq) và ghi vào 1 bảng event_store
-- duy nhất → nơi trace toàn bộ hành trình 1 giao dịch qua mọi service.
--
-- ⚠️ Vì sao KHÔNG cho 4 service cùng ghi vào 1 bảng chung?
--   → Vi phạm Database-per-Service: tạo shared-DB coupling, single point of
--     contention, cross-service transaction.
--   → Giải pháp đúng: CHỈ audit-service sở hữu bảng này, các service khác
--     không đụng vào — chúng chỉ publish event lên Kafka như bình thường.
--     audit-service lắng nghe async → loosely coupled, audit chết không ảnh
--     hưởng luồng nghiệp vụ.
--
-- Đây là mô hình gần với Event Sourcing / audit trail nghiệp vụ (business-level).
-- Observability hạ tầng (ELK, OpenTelemetry/Jaeger) là lớp bổ trợ, không thay thế.
-- ============================================================

\connect audit_db;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------
-- event_store
-- Tổng hợp mọi event xuyên suốt tất cả service.
--
-- correlation_id: xuyên suốt 1 giao dịch qua mọi service — KEY để trace.
--   Query: SELECT * FROM event_store WHERE correlation_id = ? ORDER BY occurred_at
--   → thấy toàn bộ hành trình: UserRegistered → DebitCommand → DebitCompleted → ...
--
-- causation_id: event_id của event đã GÂY RA event này → dựng cây nhân quả
--   (event A trigger event B trigger event C...).
--
-- source_service: service nào phát ra event (user/wallet/payment/notification).
-- occurred_at: thời điểm event SINH RA (lấy từ envelope, do service gốc set).
-- recorded_at: thời điểm audit-service GHI vào DB (có thể lệch vài ms do async).
--
-- id BIGSERIAL: thứ tự ghi nhận theo audit-service (FIFO khi replay/scan).
--
-- ⚠️ Idempotency: event_id UNIQUE — Kafka at-least-once có thể giao trùng,
--   audit-service INSERT rồi bắt duplicate-key → bỏ qua, tránh ghi 2 lần.
-- ------------------------------------------------------------
CREATE TABLE event_store (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL,
    correlation_id UUID         NOT NULL,
    causation_id   UUID,
    source_service VARCHAR(50)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    aggregate_id   UUID,                    -- thường là transactionId hoặc userId
    payload        JSONB        NOT NULL,
    occurred_at    TIMESTAMP    NOT NULL,
    recorded_at    TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT event_store_event_id_uk UNIQUE (event_id)
);

-- Trace theo hành trình 1 giao dịch — index chính, dùng nhiều nhất
CREATE INDEX idx_event_store_correlation ON event_store (correlation_id, occurred_at);
-- Trace theo aggregate (transactionId)
CREATE INDEX idx_event_store_aggregate   ON event_store (aggregate_id);
-- Lọc theo loại event / service khi điều tra
CREATE INDEX idx_event_store_type        ON event_store (event_type);
CREATE INDEX idx_event_store_source      ON event_store (source_service);
CREATE INDEX idx_event_store_recorded_at ON event_store (recorded_at DESC);
