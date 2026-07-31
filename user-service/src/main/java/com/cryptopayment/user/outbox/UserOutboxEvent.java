package com.cryptopayment.user.outbox;

import com.cryptopayment.common.messaging.outbox.AbstractOutboxEvent;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Bản ghi outbox của user-service — map bảng user_db.user_outbox_events.
 * Toàn bộ cột & hành vi (markPublished/markFailed/incrementRetry) kế thừa từ
 * {@link AbstractOutboxEvent}; ở đây chỉ cần khai đúng tên bảng.
 * <p>
 * CỐ Ý nằm ở package {@code outbox} chứ KHÔNG phải {@code domain}: các field
 * topic/status/retryCount/errorMessage/publishedAt là cơ chế giao vận message,
 * không mang ý nghĩa nghiệp vụ nào. Domain của service này chỉ có
 * {@link com.cryptopayment.user.entity.User}.
 */
@Entity
@Table(name = "user_outbox_events")
public class UserOutboxEvent extends AbstractOutboxEvent {
}
