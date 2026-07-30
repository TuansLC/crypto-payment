package com.cryptopayment.common.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Danh mục mã lỗi nghiệp vụ dùng chung, kèm HTTP status mặc định.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Không xác thực được"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Không có quyền truy cập"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên"),
    CONFLICT(HttpStatus.CONFLICT, "Tài nguyên đã tồn tại"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "Yêu cầu đang được xử lý"),

    // Nghiệp vụ ví / thanh toán
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Số dư không đủ"),
    WALLET_FROZEN(HttpStatus.UNPROCESSABLE_ENTITY, "Ví đang bị khóa"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy ví"),
    SELF_TRANSFER_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Không thể tự chuyển cho chính mình"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
