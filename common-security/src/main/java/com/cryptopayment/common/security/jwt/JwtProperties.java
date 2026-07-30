package com.cryptopayment.common.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình JWT, bind từ application.yml:
 * <pre>
 * app:
 *   jwt:
 *     secret: &lt;secret &gt;= 256 bit&gt;
 *     expiration-ms: 86400000
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** Khóa ký HMAC — PHẢI >= 32 ký tự (256 bit) cho HS256. Production: để trong secret manager. */
    private String secret;

    /** Thời gian sống của token (ms). Mặc định 24h. */
    private long expirationMs = 86_400_000L;
}
