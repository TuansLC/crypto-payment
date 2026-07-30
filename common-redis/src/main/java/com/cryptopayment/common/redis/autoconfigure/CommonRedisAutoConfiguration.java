package com.cryptopayment.common.redis.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.cryptopayment.common.redis.idempotency.IdempotencyService;
import com.cryptopayment.common.redis.lock.DistributedLock;

/**
 * Auto-configuration cho common-redis. Spring Boot đã tự tạo StringRedisTemplate
 * khi có spring-data-redis + connection factory; ta chỉ đăng ký thêm 2 helper.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
public class CommonRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public IdempotencyService idempotencyService(StringRedisTemplate redisTemplate) {
        return new IdempotencyService(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public DistributedLock distributedLock(StringRedisTemplate redisTemplate) {
        return new DistributedLock(redisTemplate);
    }
}
