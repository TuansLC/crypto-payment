package com.cryptopayment.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request đăng nhập. Cố ý trả lỗi chung chung "sai tài khoản hoặc mật khẩu" ở
 * tầng service (không phân biệt sai username hay sai password) để tránh lộ việc
 * username có tồn tại hay không (chống user enumeration).
 */
public record LoginRequest(

        @NotBlank(message = "username không được để trống")
        String username,

        @NotBlank(message = "password không được để trống")
        String password) {
}
