package com.cryptopayment.common.core.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vỏ response REST thống nhất cho MỌI microservice — cả success lẫn error.
 * <p>
 * {@code @JsonInclude(NON_NULL)}: field null bị bỏ khỏi JSON → response gọn
 * (success không có errors/path, error không có data).
 *
 * @param <T> kiểu dữ liệu trả về khi thành công
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** true = thành công, false = lỗi. */
    private boolean success;

    /** Mã nghiệp vụ ổn định: "OK" hoặc ErrorCode (vd "INSUFFICIENT_BALANCE"). */
    private String code;

    /** Thông điệp người đọc được. */
    private String message;

    /** Dữ liệu trả về (chỉ khi success). */
    private T data;

    /** traceId để tra log/observability — lấy từ Micrometer (MDC). */
    private String traceId;

    /** Endpoint gây lỗi, vd "/api/payments/transfer" (chỉ khi error). */
    private String path;

    /** Danh sách lỗi chi tiết (validation field-level, chỉ khi error). */
    private List<ErrorDetail> errors;

    /** Thời điểm tạo response (UTC). */
    private Instant timestamp;

    // ---------- Success ----------

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code("OK")
                .data(data)
                .timestamp(Instant.now())
                .build();
    }

    // ---------- Error ----------

    public static ApiResponse<Void> error(String code, String message) {
        return ApiResponse.<Void>builder()
                .success(false)
                .code(code)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
