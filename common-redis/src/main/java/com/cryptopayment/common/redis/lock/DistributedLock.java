package com.cryptopayment.common.redis.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Distributed Lock đơn giản bằng Redis SETNX + release atomic bằng Lua.
 * <p>
 * - Acquire: SETNX key = token ngẫu nhiên, kèm TTL (tự hết hạn nếu instance chết).
 * - Release: Lua script CHỈ xóa nếu value == token của mình → tránh xóa nhầm lock
 *   của instance khác (bug kinh điển khi lock hết hạn giữa chừng).
 * <p>
 * ⚠️ Hạn chế: TTL cố định. Nếu job chạy lâu hơn TTL → lock hết hạn sớm, instance
 * khác có thể chiếm. Production cần watchdog gia hạn lease (Redisson) hoặc fencing
 * token. Với hệ này, ưu tiên thiết kế LOCK-FREE (Optimistic Lock + Idempotency +
 * SKIP LOCKED cho outbox); DistributedLock chỉ dùng cho hot-path hiếm.
 */
public class DistributedLock {

    /** CHỈ xóa key nếu đang giữ đúng token của mình (release an toàn). */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public DistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Thử chiếm lock.
     * @return token nếu chiếm được (dùng để unlock); null nếu lock đang bị giữ.
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    /**
     * Nhả lock — chỉ thành công nếu token khớp (mình là chủ lock).
     */
    public boolean unlock(String key, String token) {
        Long result = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
        return Long.valueOf(1L).equals(result);
    }

    /**
     * Chạy {@code action} trong lock; tự nhả lock ở finally.
     *
     * <p>⚠️ <b>TTL CỐ ĐỊNH, KHÔNG tự động gia hạn (không có watchdog).</b> Phải đảm bảo
     * {@code ttl} lớn hơn NHIỀU so với thời gian dự kiến chạy xong {@code action}.
     * Nếu {@code action} chạy lâu hơn {@code ttl} → lock hết hạn giữa chừng, instance khác
     * có thể chiếm lock và chạy song song → duplicate.
     *
     * <p>Với job xử lý batch (vd OutboxPublisherJob): <b>giới hạn kích thước batch</b>
     * (LIMIT 100) để luôn chạy xong trong TTL, thay vì cần watchdog phức tạp. TUYỆT ĐỐI
     * không tăng batch size lên rất lớn mà quên tăng TTL tương ứng. Cần watchdog thật thì
     * dùng Redisson.
     *
     * @return Optional chứa kết quả nếu chiếm được lock; empty nếu lock đang bận.
     */
    public <T> Optional<T> withLock(String key, Duration ttl, Supplier<T> action) {
        String token = tryLock(key, ttl);
        if (token == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            unlock(key, token);
        }
    }
}
