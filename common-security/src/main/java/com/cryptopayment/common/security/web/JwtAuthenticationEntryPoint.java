package com.cryptopayment.common.security.web;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Trả 401 dạng ApiResponse chuẩn (code UNAUTHORIZED + traceId) khi truy cập endpoint
 * cần auth mà thiếu/sai token.
 * <p>
 * Viết JSON THỦ CÔNG (không dùng ObjectMapper) để tránh phụ thuộc Jackson version
 * (Boot 4 = Jackson 3, tools.jackson) — body cố định, nhỏ nên viết tay an toàn.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get("traceId");
        String traceIdJson = traceId != null ? "\"" + traceId + "\"" : "null";

        String body = """
                {"success":false,"code":"UNAUTHORIZED","message":"Không xác thực được",\
                "traceId":%s,"path":"%s","timestamp":"%s"}"""
                .formatted(traceIdJson, request.getRequestURI(), Instant.now().toString());

        response.getWriter().write(body);
    }
}
