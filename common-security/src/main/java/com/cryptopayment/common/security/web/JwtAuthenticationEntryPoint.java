package com.cryptopayment.common.security.web;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.cryptopayment.common.core.observability.TraceConstants;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Trả 401 dạng ApiResponse chuẩn (code UNAUTHORIZED + traceId) khi truy cập endpoint
 * cần auth mà thiếu/sai token.
 * <p>
 * Field name + cấu trúc phẳng khớp HỆT {@code ApiResponse} của common-core
 * (success/code/message/traceId/path/timestamp) → client xử lý 1 format duy nhất cho
 * cả lỗi từ GlobalExceptionHandler lẫn lỗi 401 từ Security. traceId ẩn khi null (giống NON_NULL).
 * <p>
 * ⚠️ Viết JSON THỦ CÔNG (không dùng ObjectMapper) để né phụ thuộc Jackson version
 * (Boot 4 = Jackson 3). Chỉ dùng cho body 401 cố định, nhỏ. {@code path} có escape tối
 * thiểu (" và \) — KHÔNG nhân rộng cách viết tay này cho JSON phức tạp hơn.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get(TraceConstants.MDC_TRACE_ID);

        StringBuilder json = new StringBuilder(256);
        json.append("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"Không xác thực được\"");
        if (traceId != null) {
            json.append(",\"traceId\":\"").append(escape(traceId)).append('"');
        }
        json.append(",\"path\":\"").append(escape(request.getRequestURI())).append('"');
        json.append(",\"timestamp\":\"").append(Instant.now()).append("\"}");

        response.getWriter().write(json.toString());
    }

    /** Escape tối thiểu cho JSON string value. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
