package com.cryptopayment.common.messaging;

/**
 * Tên Kafka header dùng chung cho mọi message — nguồn chân lý duy nhất.
 * Producer (EventPublisher, OutboxPublisherJob) và consumer đều dùng hằng số này,
 * tránh gõ tay lệch chuỗi làm consumer đọc sai header âm thầm.
 */
public final class EventHeaders {

    private EventHeaders() {
    }

    /** ID duy nhất của event — dùng cho idempotency ở consumer. */
    public static final String EVENT_ID = "eventId";

    /** correlationId — trace nghiệp vụ xuyên service (khớp MDC key correlationId). */
    public static final String CORRELATION_ID = "correlationId";

    /** Loại event (UserRegistered, DebitCommand...). */
    public static final String EVENT_TYPE = "eventType";
}
