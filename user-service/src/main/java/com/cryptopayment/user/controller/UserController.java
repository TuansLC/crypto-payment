package com.cryptopayment.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cryptopayment.common.core.dto.ApiResponse;
import com.cryptopayment.common.security.jwt.AuthenticatedUser;
import com.cryptopayment.user.dto.AuthResponse;
import com.cryptopayment.user.dto.LoginRequest;
import com.cryptopayment.user.dto.RegisterRequest;
import com.cryptopayment.user.dto.UserResponse;
import com.cryptopayment.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST endpoints của user-service.
 * <ul>
 *   <li>POST /api/users/register — public, 201 + JWT</li>
 *   <li>POST /api/users/login    — public, 200 + JWT</li>
 *   <li>GET  /api/users/me       — cần Bearer JWT, trả profile</li>
 * </ul>
 * Response luôn bọc trong {@link ApiResponse}; lỗi do GlobalExceptionHandler (common-core) xử lý.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(userService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(userService.getById(principal.userId()));
    }
}
