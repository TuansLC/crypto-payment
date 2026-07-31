package com.cryptopayment.user.dto;

import com.cryptopayment.user.entity.User;

/**
 * View công khai của User trả về client — KHÔNG chứa passwordHash.
 */
public record UserResponse(
        String id,
        String username,
        String email,
        String fullName,
        String status) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus().name());
    }
}
