package com.cryptopayment.common.core.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Base cho entity có cả created_at & updated_at.
 * Dùng lifecycle callback (không cần @EnableJpaAuditing) → tự set thời gian.
 * <p>
 * Instant luôn biểu diễn UTC (epoch-based), không phụ thuộc timezone của JVM
 * — đây là lý do chọn Instant thay vì LocalDateTime cho timestamp trong hệ phân tán.
 * Map sang PostgreSQL TIMESTAMP (không TZ) — khớp với cột created_at/updated_at
 * trong init-db/*.sql. Giá trị lưu trong DB mặc định hiểu là UTC.
 * <p>
 * Lưu ý: KHÔNG khai báo id ở đây vì kiểu id khác nhau giữa các bảng
 * (UUID cho bảng nghiệp vụ, BIGSERIAL cho outbox). Entity tự định nghĩa id.
 * <p>
 * LƯU Ý QUAN TRỌNG: entity con PHẢI tự viết equals()/hashCode() dựa trên id,
 * KHÔNG dùng @Data hoặc @EqualsAndHashCode mặc định của Lombok
 * (dễ gây lỗi do so sánh luôn cả createdAt/updatedAt, ảnh hưởng Set/Map/JPA merge).
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}