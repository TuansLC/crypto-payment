package com.cryptopayment.common.redis.idempotency;

/**
 * Trạng thái xử lý của một idempotency key — khớp với cột status trong
 * DB idempotency_keys / payment_idempotency_keys.
 */
public enum IdempotencyStatus {
    /** Đang xử lý lần đầu. */
    PROCESSING,
    /** Đã xử lý xong (có thể có cached result để trả lại). */
    COMPLETED,
    /** Xử lý thất bại. */
    FAILED
}
