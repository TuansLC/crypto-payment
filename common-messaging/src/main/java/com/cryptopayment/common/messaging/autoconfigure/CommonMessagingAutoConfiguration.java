package com.cryptopayment.common.messaging.autoconfigure;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.cryptopayment.common.messaging.publisher.EventPublisher;

/**
 * Auto-configuration cho common-messaging: chỉ cần add dependency + có KafkaTemplate
 * (Boot tự tạo từ application.yml) là các bean dùng chung được đăng ký sẵn.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
public class CommonMessagingAutoConfiguration {

    /** Wrapper publish EventEnvelope kèm header. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public EventPublisher eventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new EventPublisher(kafkaTemplate);
    }

    /**
     * Recoverer đẩy message xử lý-không-được sang topic "{original}.DLT".
     * ĐÂY LÀ consumer-error DLT (hạ tầng), KHÁC với business DLQ "wallet.dlq"
     * (do payment-service tự publish khi compensation fail).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, Object> template) {
        return new DeadLetterPublishingRecoverer(template);
    }

    /**
     * Error handler chung cho MỌI @KafkaListener: retry với exponential backoff,
     * hết số lần thì đẩy sang "{topic}.DLT". Boot tự gắn CommonErrorHandler này vào
     * listener container.
     * <p>
     * ExponentialBackOff(1s, x2) + maxAttempts=3 → 1s, 2s rồi vào DLT. Backoff tăng dần
     * để lỗi tạm thời (DB timeout...) có thời gian hồi phục, không nghẽn consumer.
     * <p>
     * ⚠️ Poison pill: lỗi DESERIALIZE xảy ra TRƯỚC khi listener chạy → chỉ được handler
     * này bắt & đẩy DLT nếu consumer bọc value bằng ErrorHandlingDeserializer
     * (đã cấu hình ở application.yml). Nếu không sẽ crash-loop vô hạn.
     * <p>
     * ⚠️ Commit offset: listener là ĐỒNG BỘ + @Transactional (ack-mode=RECORD) → container
     * commit offset SAU KHI method trả về thành công; exception (kể cả rollback) → KHÔNG
     * commit → message được retry/DLT. TUYỆT ĐỐI KHÔNG dùng @Async trong listener,
     * sẽ phá vỡ nguyên tắc commit-after-success này.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(DeadLetterPublishingRecoverer.class)
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(3);   // 3 lần thử: 1s, 2s rồi vào DLT
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /**
     * Template byte[] riêng cho Outbox Publisher — gửi payload JSON đã serialize
     * dưới dạng raw bytes (tránh double-encode của JsonSerializer).
     * Cấu hình tối thiểu từ bootstrap-servers (version-agnostic, không dùng KafkaProperties API).
     */
    @Bean
    @ConditionalOnMissingBean(name = "outboxBytesKafkaTemplate")
    public KafkaTemplate<String, byte[]> outboxBytesKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }
}
