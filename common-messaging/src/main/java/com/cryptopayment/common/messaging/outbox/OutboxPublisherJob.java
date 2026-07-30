package com.cryptopayment.common.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbox Publisher generic — trái tim của Outbox Pattern.
 * <p>
 * Cơ chế: mỗi chu kỳ đọc một batch PENDING bằng {@code FOR UPDATE SKIP LOCKED}
 * (qua {@link OutboxStore}), publish lên Kafka, chờ broker ack rồi mới đánh dấu
 * PUBLISHED. SKIP LOCKED cho nhiều instance chạy song song mà không giẫm chân nhau
 * (thay cho Redis distributed lock).
 * <p>
 * Publish RAW BYTES: payload đã là JSON của EventEnvelope (service serialize khi
 * ghi outbox) → gửi nguyên bytes để consumer JsonDeserializer đọc đúng, tránh
 * double-encode. Dùng template byte[] riêng (xem CommonMessagingAutoConfiguration).
 * <p>
 * Mỗi service đăng ký 1 bean:
 * <pre>{@code
 * @Bean
 * public OutboxPublisherJob<UserOutboxEvent> userOutboxJob(
 *         UserOutboxStore store, KafkaTemplate<String, byte[]> outboxBytesKafkaTemplate) {
 *     return new OutboxPublisherJob<>(store, outboxBytesKafkaTemplate, 100, 3);
 * }
 * }</pre>
 * và bật @EnableScheduling.
 *
 * @param <E> entity outbox cụ thể của service
 */
@Slf4j
public class OutboxPublisherJob<E extends AbstractOutboxEvent> {

    private static final long ACK_TIMEOUT_SECONDS = 10L;

    private final OutboxStore<E> store;
    private final KafkaTemplate<String, byte[]> bytesKafkaTemplate;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisherJob(OutboxStore<E> store,
                              KafkaTemplate<String, byte[]> bytesKafkaTemplate,
                              int batchSize,
                              int maxRetries) {
        this.store = store;
        this.bytesKafkaTemplate = bytesKafkaTemplate;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<E> batch = store.fetchPending(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox: xử lý {} event PENDING", batch.size());
        for (E event : batch) {
            publishOne(event);
            store.save(event);
        }
    }

    private void publishOne(E event) {
        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                    event.getTopic(),
                    event.getAggregateId().toString(),
                    event.getPayload().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));

            // CHỜ broker ack rồi mới mark PUBLISHED — tránh mất event nếu Kafka lỗi
            bytesKafkaTemplate.send(record).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
        } catch (Exception ex) {
            event.incrementRetry();
            event.setErrorMessage(ex.getMessage());
            if (event.getRetryCount() >= maxRetries) {
                event.markFailed(ex.getMessage());   // vượt ngưỡng → FAILED, cần điều tra
                log.error("Outbox event {} FAILED sau {} lần retry", event.getEventId(), maxRetries, ex);
            } else {
                // giữ PENDING → chu kỳ sau thử lại
                log.warn("Outbox event {} publish lỗi (retry {}/{}): {}",
                        event.getEventId(), event.getRetryCount(), maxRetries, ex.getMessage());
            }
        }
    }
}
