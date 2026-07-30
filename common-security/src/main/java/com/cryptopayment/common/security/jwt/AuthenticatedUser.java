package com.cryptopayment.common.security.jwt;

/**
 * Principal đặt vào SecurityContext sau khi verify JWT.
 * Controller lấy qua {@code @AuthenticationPrincipal AuthenticatedUser user}.
 * <p>
 * KHÔNG có roles: hệ thống này không dùng RBAC — user chỉ thao tác trên tài khoản
 * của chính mình, ownership check bằng cách so userId ở tầng service. Nếu sau này
 * cần phân quyền (admin...) mới thêm roles + AccessDeniedHandler.
 *
 * @param userId   id người dùng (subject của token)
 * @param username tên đăng nhập
 */
public record AuthenticatedUser(String userId, String username) {
}
