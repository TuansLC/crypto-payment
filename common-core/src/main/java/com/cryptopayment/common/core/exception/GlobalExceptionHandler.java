package com.cryptopayment.common.core.exception;

import java.time.Instant;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cryptopayment.common.core.dto.ApiResponse;
import com.cryptopayment.common.core.dto.ErrorDetail;
import com.cryptopayment.common.core.observability.TraceConstants;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Bắt exception chung cho MỌI service → trả về {@link ApiResponse} error thống nhất
 * (code nghiệp vụ + traceId + path + errors[]). Đăng ký tự động qua auto-config.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        ErrorCode code = ex.getErrorCode();
        log.warn("Business exception [{}] at {}: {}", code, req.getRequestURI(), ex.getMessage());
        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .code(code.name())
                .message(ex.getMessage())
                .traceId(currentTraceId())
                .path(req.getRequestURI())
                .timestamp(Instant.now())
                .build();
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest req) {
        List<ErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> ErrorDetail.of(f.getField(), f.getDefaultMessage()))
                .toList();
        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .code(ErrorCode.VALIDATION_ERROR.name())
                .message(ErrorCode.VALIDATION_ERROR.getDefaultMessage())
                .traceId(currentTraceId())
                .path(req.getRequestURI())
                .errors(errors)
                .timestamp(Instant.now())
                .build();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest req) {
        // Log full stacktrace (correlate bằng traceId), KHÔNG lộ chi tiết nội bộ ra response.
        log.error("Unexpected error at {}", req.getRequestURI(), ex);
        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .code(ErrorCode.INTERNAL_ERROR.name())
                .message(ErrorCode.INTERNAL_ERROR.getDefaultMessage())
                .traceId(currentTraceId())
                .path(req.getRequestURI())
                .timestamp(Instant.now())
                .build();
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus()).body(body);
    }

    /** traceId do Micrometer Tracing đặt vào MDC; null nếu chưa bật tracing. */
    private String currentTraceId() {
        return MDC.get(TraceConstants.MDC_TRACE_ID);
    }
}
