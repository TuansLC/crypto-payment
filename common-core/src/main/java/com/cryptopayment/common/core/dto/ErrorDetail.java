package com.cryptopayment.common.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Một lỗi chi tiết trong mảng {@code errors} của {@link ApiResponse}.
 * Dùng cho validation field-level (field nào sai) hoặc nhiều lỗi nghiệp vụ cùng lúc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDetail {

    /** Tên field bị lỗi (chỉ có với lỗi validation, nullable). */
    private String field;

    /** Mã lỗi nghiệp vụ (nullable). */
    private String code;

    /** Thông điệp mô tả lỗi. */
    private String message;

    public static ErrorDetail of(String field, String message) {
        return ErrorDetail.builder().field(field).message(message).build();
    }
}
