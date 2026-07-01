-- ============================================================
-- 01-create-databases.sql
-- Tạo 4 databases cho 4 microservices
-- Chạy đầu tiên (alphabet order trong docker-entrypoint-initdb.d)
-- ============================================================

CREATE DATABASE user_db;
CREATE DATABASE wallet_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
