package com.cryptopayment.common.core.observability;

/**
 * Hằng số dùng chung cho tracing. Micrometer Tracing đặt traceId/spanId vào MDC
 * với các key này → mọi nơi đọc MDC (GlobalExceptionHandler, JwtAuthenticationEntryPoint...)
 * PHẢI dùng hằng số này thay vì hardcode chuỗi, tránh lệch tên key.
 */
public final class TraceConstants {

    private TraceConstants() {
    }

    /** Key của traceId trong MDC (chuẩn Micrometer Tracing) — observability trong 1 hop. */
    public static final String MDC_TRACE_ID = "traceId";

    /** Key của spanId trong MDC (chuẩn Micrometer Tracing). */
    public static final String MDC_SPAN_ID = "spanId";

    /**
     * Key của correlationId trong MDC — trace key NGHIỆP VỤ bền vững, xuyên suốt nhiều
     * service KỂ CẢ qua Outbox + Kafka (async). Khác traceId của Micrometer (chỉ sống
     * trong 1 hop đồng bộ, ĐỨT khi qua Outbox vì publish ở thread scheduler khác).
     * <p>
     * Quy ước: lúc tạo outbox event, set envelope.correlationId = traceId hiện tại;
     * mỗi @KafkaListener restore MDC.put(MDC_CORRELATION_ID, envelope.getCorrelationId()).
     */
    public static final String MDC_CORRELATION_ID = "correlationId";
}
