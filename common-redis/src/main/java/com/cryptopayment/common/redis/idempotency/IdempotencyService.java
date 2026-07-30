package com.cryptopayment.common.redis.idempotency;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Idempotency bằng Redis — hỗ trợ CẢ 2 pattern:
 * <p>
 * 1) Fast-path cho Kafka consumer: {@link #tryBegin} chỉ cần biết "đã xử lý chưa".
 * 2) API-level: lưu status + cached result để trả lại NGAY cho client khi
 *    request bị lặp (không chạy lại logic).
 * <p>
 * Mỗi key lưu dưới dạng Redis Hash: field {@code status} + field {@code result} (JSON string).
 * <p>
 * ⚠️ Redis là lớp NHANH, KHÔNG phải nguồn chân lý. Nguồn chân lý bền vững là bảng
 * DB (idempotency_keys / payment_idempotency_keys), ghi trong cùng transaction nghiệp vụ.
 * Nếu chỉ dựa Redis: SETNX xong rồi transaction rollback/crash → key vẫn còn → mất event.
 * <p>
 * Sinh key bằng {@link IdempotencyKeys} — KHÔNG tự nối chuỗi.
 */
public class IdempotencyService {

    private static final String FIELD_STATUS = "status";
    private static final String FIELD_RESULT = "result";

    private final StringRedisTemplate redisTemplate;
    private final HashOperations<String, Object, Object> hashOps;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.hashOps = redisTemplate.opsForHash();
    }

    /**
     * Bắt đầu xử lý key nếu chưa tồn tại (đặt status=PROCESSING).
     * @return true nếu ĐÂY là lần đầu (được phép xử lý); false nếu đã tồn tại (duplicate).
     */
    public boolean tryBegin(String key, Duration ttl) {
        Boolean first = hashOps.putIfAbsent(key, FIELD_STATUS, IdempotencyStatus.PROCESSING.name());
        if (Boolean.TRUE.equals(first)) {
            redisTemplate.expire(key, ttl);
            return true;
        }
        return false;
    }

    /** Trạng thái hiện tại của key (empty nếu chưa từng thấy). */
    public Optional<IdempotencyStatus> getStatus(String key) {
        Object value = hashOps.get(key, FIELD_STATUS);
        return value == null ? Optional.empty() : Optional.of(IdempotencyStatus.valueOf(value.toString()));
    }

    /** Cached result (JSON string) — caller tự deserialize sang DTO. Empty nếu chưa có. */
    public Optional<String> getCachedResult(String key) {
        Object value = hashOps.get(key, FIELD_RESULT);
        return Optional.ofNullable(value).map(Object::toString);
    }

    /** Đánh dấu COMPLETED và cache result (JSON) để trả lại khi duplicate. */
    public void markCompleted(String key, String resultJson, Duration ttl) {
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_STATUS, IdempotencyStatus.COMPLETED.name());
        if (resultJson != null) {
            fields.put(FIELD_RESULT, resultJson);
        }
        hashOps.putAll(key, fields);
        redisTemplate.expire(key, ttl);
    }

    /** Đánh dấu FAILED (cho phép client/consumer thử lại tùy nghiệp vụ). */
    public void markFailed(String key, Duration ttl) {
        hashOps.put(key, FIELD_STATUS, IdempotencyStatus.FAILED.name());
        redisTemplate.expire(key, ttl);
    }

    /** Gỡ key (hiếm dùng — cho phép xử lý lại từ đầu). */
    public void remove(String key) {
        redisTemplate.delete(key);
    }
}
