package com.cryptopayment.common.messaging.outbox;

/**
 * Trạng thái của một outbox event.
 */
public enum OutboxStatus {
    /** Vừa ghi, chờ publisher đẩy lên Kafka. */
    PENDING,
    /** Đã publish thành công (broker ack). */
    PUBLISHED,
    /** Retry hết số lần vẫn thất bại — cần điều tra. */
    FAILED
}
