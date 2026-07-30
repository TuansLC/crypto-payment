package com.cryptopayment.common.security.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long-xxxx";

    private JwtService service(long expirationMs) {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setExpirationMs(expirationMs);
        return new JwtService(props);
    }

    @Test
    void generateAndParse_valid() {
        JwtService svc = service(60_000L);
        String token = svc.generateToken("u-1", "alice");

        AuthenticatedUser user = svc.parseAndValidate(token);

        assertEquals("u-1", user.userId());
        assertEquals("alice", user.username());
    }

    @Test
    void parse_wrongSignature_throws() {
        String token = service(60_000L).generateToken("u-1", "alice");

        JwtProperties other = new JwtProperties();
        other.setSecret("another-secret-key-at-least-32-characters-diff");
        JwtService verifier = new JwtService(other);

        assertThrows(JwtException.class, () -> verifier.parseAndValidate(token));
    }

    @Test
    void parse_expired_throws() {
        JwtService svc = service(-1_000L);   // expiration = now - 1s → đã hết hạn
        String token = svc.generateToken("u-1", "alice");

        assertThrows(ExpiredJwtException.class, () -> svc.parseAndValidate(token));
    }
}
