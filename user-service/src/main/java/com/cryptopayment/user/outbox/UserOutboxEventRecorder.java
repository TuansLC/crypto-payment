package com.cryptopayment.user.outbox;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.cryptopayment.common.core.event.EventEnvelope;
import com.cryptopayment.common.core.event.EventTypes;
import com.cryptopayment.common.core.event.Topics;
import com.cryptopayment.common.core.exception.BusinessException;
import com.cryptopayment.common.core.exception.ErrorCode;
import com.cryptopayment.common.core.observability.TraceConstants;
import com.cryptopayment.common.messaging.outbox.OutboxStatus;
import com.cryptopayment.user.entity.User;
import com.cryptopayment.user.event.UserEventRecorder;
import com.cryptopayment.user.event.UserRegisteredPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter hiện thực {@link UserEventRecorder} bằng Outbox Pattern — ghi event vào
 * bảng {@code user_outbox_events} thay vì gọi Kafka trực tiếp.
 * <p>
 * Đây là INFRASTRUCTURE: port {@link UserEventRecorder} nằm ở package {@code event},
 * adapter này nằm ở {@code outbox} cùng entity/repository/store. Muốn đổi sang
 * Debezium CDC hay publish trực tiếp thì viết adapter khác, service không đổi.
 * <p>
 * Payload lưu là JSON của TOÀN BỘ {@link EventEnvelope} → {@code OutboxPublisherJob}
 * gửi nguyên raw bytes lên Kafka, consumer deserialize đúng envelope, tránh double-encode.
 * <p>
 * KHÔNG tự mở transaction: cố ý tham gia transaction của caller
 * ({@code UserServiceImpl#register}) để INSERT users và INSERT outbox cùng sống/chết.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserOutboxEventRecorder implements UserEventRecorder {

    /** sourceService gắn vào EventEnvelope — khớp quy ước "user" ở các service khác. */
    private static final String SOURCE_SERVICE = "user";

    private final UserOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void recordUserRegistered(User user) {
        UserRegisteredPayload payload = new UserRegisteredPayload(
                user.getId().toString(), user.getUsername(), user.getEmail(), user.getFullName());

        EventEnvelope<UserRegisteredPayload> envelope = EventEnvelope.newEvent(
                EventTypes.USER_REGISTERED, SOURCE_SERVICE, currentCorrelationId(), payload);

        outboxRepository.save(toOutboxRecord(envelope, user.getId()));
        log.debug("Đã ghi outbox {} cho userId={}", EventTypes.USER_REGISTERED, user.getId());
    }

    private UserOutboxEvent toOutboxRecord(EventEnvelope<?> envelope, UUID aggregateId) {
        UserOutboxEvent record = new UserOutboxEvent();
        record.setEventId(UUID.fromString(envelope.getEventId()));
        record.setAggregateId(aggregateId);
        record.setEventType(envelope.getEventType());
        record.setTopic(Topics.USER_EVENTS);
        record.setPayload(serialize(envelope));
        record.setStatus(OutboxStatus.PENDING);
        return record;
    }

    /**
     * correlationId nên = traceId (Micrometer) để log, DB và Kafka khớp nhau.
     * Chưa bật tracing → sinh mới, tránh để null làm đứt mạch trace ở downstream.
     */
    private String currentCorrelationId() {
        String correlationId = MDC.get(TraceConstants.MDC_TRACE_ID);
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException ex) {
            // Chỉ bắt lỗi serialize (Jackson 3 ném unchecked JacksonException) — KHÔNG bắt
            // RuntimeException chung, tránh nuốt bug thật rồi gán nhãn sai nguyên nhân.
            log.error("Không thể serialize EventEnvelope eventId={}", envelope.getEventId(), ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Không thể tạo event UserRegistered");
        }
    }
}
