package com.cryptopayment.user.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.cryptopayment.common.messaging.outbox.OutboxPublisherJob;
import com.cryptopayment.common.messaging.outbox.OutboxStore;

/**
 * Wiring của cơ chế Outbox. Đặt CÙNG package với entity/repository/store/recorder
 * để toàn bộ Outbox Pattern đọc được ở một chỗ — {@code config/} chỉ giữ cấu hình
 * xuyên suốt ứng dụng (security), không giữ wiring của một cơ chế cụ thể.
 * <p>
 * Tham số phụ thuộc là {@link OutboxStore} (abstraction), KHÔNG phải
 * {@link UserOutboxStore} (implementation) — đúng DIP: đổi cách truy cập outbox
 * (JDBC thuần, jOOQ...) chỉ cần thay bean implement interface, config này không đổi.
 * Spring khớp bean theo generic type {@code OutboxStore<UserOutboxEvent>}.
 * <p>
 * {@code outboxBytesKafkaTemplate} (KafkaTemplate&lt;String,byte[]&gt;) do
 * common-messaging auto-config cung cấp — inject theo TÊN tham số để không nhầm
 * với KafkaTemplate&lt;String,Object&gt; mặc định của Boot.
 * <p>
 * {@code @EnableScheduling} KHÔNG đặt ở đây mà ở {@code UserServiceApplication}:
 * bật scheduler là việc ở tầng ứng dụng. Nếu gắn vào config của outbox thì sau này
 * bỏ outbox đi sẽ tắt luôn scheduler của mọi job khác — một lỗi rất khó truy.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    @Bean
    public OutboxPublisherJob<UserOutboxEvent> userOutboxJob(
            OutboxStore<UserOutboxEvent> outboxStore,
            KafkaTemplate<String, byte[]> outboxBytesKafkaTemplate,
            OutboxProperties properties) {
        return new OutboxPublisherJob<>(
                outboxStore,
                outboxBytesKafkaTemplate,
                properties.batchSize(),
                properties.maxRetries());
    }
}
