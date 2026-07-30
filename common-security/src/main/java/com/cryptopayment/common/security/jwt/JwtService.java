package com.cryptopayment.common.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Tạo và verify JWT (thuật toán HS256, ký HMAC bằng shared secret). Đặt CẢ generate
 * + verify ở đây để mọi service dùng chung thuật toán ký + cấu trúc claim —
 * user-service dùng generate, wallet/payment dùng verify.
 * <p>
 * Trade-off (câu hỏi phỏng vấn "sao không RSA?"): HS256 dùng shared secret — mọi
 * service phải giữ cùng secret để verify → nếu 1 service lộ secret là toàn hệ thống
 * bị ảnh hưởng. Production nên chuyển RS256: user-service giữ PRIVATE key để ký, các
 * service khác chỉ giữ PUBLIC key để verify (không ký được) → an toàn hơn. Với demo,
 * HS256 + shared secret đủ đơn giản để minh hoạ stateless auth.
 * <p>
 * ⚠️ secret PHẢI >= 32 ký tự (256 bit) cho HS256, nếu không jjwt ném WeakKeyException.
 */
public class JwtService {

    private static final String CLAIM_USERNAME = "username";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = properties.getExpirationMs();
    }

    /** Tạo token — dùng ở user-service khi login/register. */
    public String generateToken(String userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    /**
     * Verify chữ ký + hạn, trích claim thành {@link AuthenticatedUser}.
     * @throws io.jsonwebtoken.JwtException nếu token sai chữ ký / hết hạn / malformed.
     */
    public AuthenticatedUser parseAndValidate(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new AuthenticatedUser(
                claims.getSubject(),
                claims.get(CLAIM_USERNAME, String.class));
    }
}
