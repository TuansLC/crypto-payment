package com.cryptopayment.user.event;

/**
 * Payload nghiệp vụ của event {@code UserRegistered} publish lên topic user.events.
 * <p>
 * Downstream tiêu thụ: wallet-service (tạo ví USDT balance=0 theo userId),
 * notification-service (email chào mừng theo email/fullName), audit-service (event store).
 * Chỉ chứa thông tin công khai cần thiết — KHÔNG bao giờ đưa passwordHash vào event.
 */
public record UserRegisteredPayload(
        String userId,
        String username,
        String email,
        String fullName) {
}
