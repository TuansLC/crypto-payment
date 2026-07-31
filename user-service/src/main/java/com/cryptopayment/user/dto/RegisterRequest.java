package com.cryptopayment.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request đăng ký. Validation ở đây là lớp chặn đầu tiên (fail fast),
 * MethodArgumentNotValidException sẽ được GlobalExceptionHandler map thành
 * ApiResponse error code=VALIDATION_ERROR kèm chi tiết từng field.
 */
public record RegisterRequest(

        @NotBlank(message = "username không được để trống")
        @Size(min = 3, max = 50, message = "username phải từ 3 đến 50 ký tự")
        String username,

        @NotBlank(message = "email không được để trống")
        @Email(message = "email không hợp lệ")
        @Size(max = 100, message = "email tối đa 100 ký tự")
        String email,

        @NotBlank(message = "password không được để trống")
        @Size(min = 6, max = 100, message = "password phải từ 6 đến 100 ký tự")
        String password,

        @Size(max = 100, message = "fullName tối đa 100 ký tự")
        String fullName) {
}
