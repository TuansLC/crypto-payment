package com.cryptopayment.user.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình Outbox Publisher, bind từ application.yml:
 * <pre>
 * app:
 *   outbox:
 *     poll-delay-ms: 1000
 *     batch-size: 100
 *     max-retries: 3
 * </pre>
 * <p>
 * Là {@code record} → constructor binding, config IMMUTABLE sau khi khởi động.
 * Dùng class có setter sẽ cho phép code sửa giá trị lúc runtime, làm hành vi
 * publisher lệch khỏi những gì file yml ghi — rất khó debug.
 * <p>
 * Đưa batch-size/max-retries ra config thay vì hardcode: tuning throughput theo
 * môi trường (dev/prod) không cần sửa code rồi build lại image.
 * <p>
 * Lưu ý: {@code poll-delay-ms} KHÔNG khai ở đây vì
 * {@code OutboxPublisherJob#publishPending} tự đọc trực tiếp qua
 * {@code @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")}.
 * Vẫn nên để trong yml cho mọi tham số outbox nằm cùng một chỗ.
 *
 * @param batchSize  số event lấy ra mỗi chu kỳ publish; lớn quá → transaction dài,
 *                   giữ row lock lâu, chặn các instance publisher khác
 * @param maxRetries số lần retry trước khi đánh dấu FAILED (cần điều tra thủ công)
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("3") int maxRetries) {
}
