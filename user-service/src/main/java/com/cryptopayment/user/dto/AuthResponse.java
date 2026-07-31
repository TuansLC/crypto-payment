package com.cryptopayment.user.dto;

/**
 * Kết quả của register/login: JWT + thông tin user. Client dùng accessToken cho
 * các request sau (header {@code Authorization: Bearer <token>}).
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        UserResponse user) {

    public static AuthResponse of(String accessToken, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", user);
    }
}
