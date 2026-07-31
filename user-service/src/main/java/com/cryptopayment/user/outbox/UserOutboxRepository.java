package com.cryptopayment.user.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Truy cập bảng user_outbox_events. Nằm cùng package {@code outbox} với entity,
 * store và recorder — toàn bộ cơ chế Outbox gom một chỗ thay vì rải theo tầng.
 */
public interface UserOutboxRepository extends JpaRepository<UserOutboxEvent, Long> {

    /**
     * Lấy batch event PENDING theo thứ tự id (FIFO), khóa row bằng
     * {@code FOR UPDATE SKIP LOCKED} → nhiều instance publisher chạy song song
     * không giẫm chân nhau (thay cho distributed lock).
     * <p>
     * PHẢI chạy trong transaction (của {@code OutboxPublisherJob#publishPending}),
     * vì row lock chỉ được giữ tới hết transaction.
     */
    @Query(value = """
            SELECT * FROM user_outbox_events
            WHERE status = 'PENDING'
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UserOutboxEvent> fetchPending(@Param("limit") int limit);
}
