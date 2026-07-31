package com.cryptopayment.common.messaging.publisher;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.cryptopayment.common.core.event.EventEnvelope;
import com.cryptopayment.common.messaging.EventHeaders;

import lombok.extern.slf4j.Slf4j;

/**
 * Bọc {@link KafkaTemplate} để publish {@link EventEnvelope} theo chuẩn chung:
 * - key = aggregateId/transactionId → mọi message cùng 1 giao dịch vào cùng partition
 *   → giữ đúng thứ tự tiêu thụ (quan trọng cho Saga).
 * - Gắn metadata vào Kafka header (eventId, correlationId, eventType) để consumer
 *   lọc/idempotency mà không cần deserialize payload.
 * <p>
 * Message body chính là toàn bộ EventEnvelope (JsonSerializer lo serialize).
 */
@Slf4j
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publish một envelope. Trả về CompletableFuture — caller nên chờ ack
     * (ví dụ trong Outbox Publisher) trước khi đánh dấu PUBLISHED.
     *
     * @param topic topic đích
     * @param key   partition key (thường = aggregateId/transactionId)
     */
    public CompletableFuture<SendResult<String, Object>> publish(String topic, String key,
                                                                 EventEnvelope<?> envelope) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, envelope);
        Headers headers = record.headers();
        addHeader(headers, EventHeaders.EVENT_ID, envelope.getEventId());
        addHeader(headers, EventHeaders.CORRELATION_ID, envelope.getCorrelationId());
        addHeader(headers, EventHeaders.EVENT_TYPE, envelope.getEventType());

        log.debug("Publishing event [{}] type={} correlationId={} → topic={}",
                envelope.getEventId(), envelope.getEventType(), envelope.getCorrelationId(), topic);
        return kafkaTemplate.send(record);
    }

    private void addHeader(Headers headers, String key, String value) {
        if (value != null) {
            headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
