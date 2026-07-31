package com.cryptopayment.user.common.enums;

/**
 * Trạng thái vòng đời tài khoản. Khớp CHECK ở user_db.users.status.
 * DELETED dùng cho soft-delete (kèm deleted_at), không xóa cứng bản ghi.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}
