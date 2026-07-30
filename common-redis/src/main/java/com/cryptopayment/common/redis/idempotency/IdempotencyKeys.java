package com.cryptopayment.common.redis.idempotency;

/**
 * Factory sinh idempotency key theo ĐÚNG format đã thống nhất toàn hệ thống.
 * <p>
 * MỌI service PHẢI dùng các method này thay vì tự nối chuỗi — tránh bug lệch dấu ':'
 * hoặc sai thứ tự tham số làm 2 key tưởng giống nhau nhưng không match (âm thầm phá Idempotency).
 * <p>
 * Prefix theo action → 3 lệnh cùng transactionId KHÔNG đụng key nhau.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /** debit:{transactionId}:{userId} — trừ tiền sender. */
    public static String debit(String transactionId, String userId) {
        return "debit:%s:%s".formatted(transactionId, userId);
    }

    /** credit:{transactionId}:{userId} — cộng tiền receiver. */
    public static String credit(String transactionId, String userId) {
        return "credit:%s:%s".formatted(transactionId, userId);
    }

    /** debit-reverse:{transactionId}:{userId} — hoàn tiền (compensating). */
    public static String debitReverse(String transactionId, String userId) {
        return "debit-reverse:%s:%s".formatted(transactionId, userId);
    }

    /** topup:{externalReferenceId} — nạp tiền, chống duplicate webhook. */
    public static String topup(String externalReferenceId) {
        return "topup:%s".formatted(externalReferenceId);
    }
}
