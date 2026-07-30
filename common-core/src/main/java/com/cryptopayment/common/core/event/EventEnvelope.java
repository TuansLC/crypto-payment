package com.cryptopayment.common.core.event;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bao (envelope) chuẩn cho MỌI event/command publish lên Kafka.
 * <p>
 * Tách metadata (correlationId, causationId...) khỏi payload nghiệp vụ để:
 * - Trace 1 giao dịch xuyên suốt các service qua {@code correlationId}
 *   (nên gán = traceId của Micrometer để log & DB khớp nhau).
 * - Dựng cây nhân quả qua {@code causationId} (eventId của event gây ra event này).
 * - Idempotency ở consumer dựa trên {@code eventId} (duy nhất).
 *
 * @param <T> kiểu payload nghiệp vụ (DebitCommand, UserRegistered...)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope<T> {

    /** ID duy nhất của event này — dùng cho idempotency ở consumer. */
    private String eventId;

    /** Xuyên suốt 1 giao dịch qua mọi service — key để trace. */
    private String correlationId;

    /** eventId của event đã GÂY RA event này — dựng cây nhân quả (nullable). */
    private String causationId;

    /** Loại event, ví dụ "UserRegistered", "DebitCommand". */
    private String eventType;

    /** Service phát ra event: user / wallet / payment / notification. */
    private String sourceService;

    /** Thời điểm event sinh ra (UTC). */
    private Instant occurredAt;

    /** Payload nghiệp vụ. */
    private T payload;

    /**
     * Tạo envelope mới cho một event khởi nguồn (chưa có event cha).
     */
    public static <T> EventEnvelope<T> newEvent(String eventType, String sourceService,
                                                String correlationId, T payload) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .correlationId(correlationId)
                .causationId(null)
                .eventType(eventType)
                .sourceService(sourceService)
                .occurredAt(Instant.now())
                .payload(payload)
                .build();
    }

    /**
     * Tạo envelope tiếp nối từ một event cha — tự set causationId = eventId của cha,
     * giữ nguyên correlationId để không đứt mạch trace.
     */
    public <P> EventEnvelope<P> nextEvent(String eventType, String sourceService, P payload) {
        return EventEnvelope.<P>builder()
                .eventId(UUID.randomUUID().toString())
                .correlationId(this.correlationId)
                .causationId(this.eventId)
                .eventType(eventType)
                .sourceService(sourceService)
                .occurredAt(Instant.now())
                .payload(payload)
                .build();
    }
}
