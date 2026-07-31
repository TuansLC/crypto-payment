package com.cryptopayment.user.event;

import com.cryptopayment.user.entity.User;

/**
 * Cổng ghi domain event của user (Outbox Pattern).
 * <p>
 * Tách khỏi {@code UserService} theo SRP: nghiệp vụ user không cần biết event được
 * lưu ở đâu (outbox table) hay serialize thế nào (JSON/Avro). Nhờ vậy:
 * <ul>
 *   <li>Đổi cơ chế phát event (outbox → CDC/Debezium, JSON → Avro) không sửa UserService.</li>
 *   <li>Test UserService chỉ cần stub interface này, không cần ObjectMapper + repository outbox.</li>
 * </ul>
 * <p>
 * ⚠️ Implementation PHẢI được gọi trong cùng transaction với lệnh ghi aggregate
 * (INSERT users) — đó chính là điều làm Outbox Pattern đảm bảo atomic.
 */
public interface UserEventRecorder {

    /**
     * Ghi event {@code UserRegistered} cho user vừa tạo.
     *
     * @param user user đã được persist (bắt buộc có id để làm aggregateId)
     */
    void recordUserRegistered(User user);
}
