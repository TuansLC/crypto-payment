package com.cryptopayment.common.messaging.outbox;

import java.util.List;

/**
 * Cổng truy cập bảng outbox mà {@link OutboxPublisherJob} generic phụ thuộc.
 * Mỗi service tự implement (bằng Spring Data JPA @Query native) với ĐÚNG tên bảng
 * của mình — vì tên bảng khác nhau: user_outbox_events / wallet_outbox_events / outbox_events.
 * <p>
 * {@code fetchPending} PHẢI dùng {@code FOR UPDATE SKIP LOCKED} và chạy trong
 * transaction của job, để nhiều instance publish song song không giẫm chân nhau.
 *
 * @param <E> entity outbox cụ thể của service
 */
public interface OutboxStore<E extends AbstractOutboxEvent> {

    /**
     * Lấy tối đa {@code limit} bản ghi PENDING, khóa row bằng SKIP LOCKED.
     * Ví dụ query native:
     * <pre>
     * SELECT * FROM user_outbox_events
     * WHERE status = 'PENDING'
     * ORDER BY id
     * LIMIT :limit
     * FOR UPDATE SKIP LOCKED
     * </pre>
     */
    List<E> fetchPending(int limit);

    /** Lưu lại trạng thái event sau khi publish (PUBLISHED / retry / FAILED). */
    void save(E event);
}
