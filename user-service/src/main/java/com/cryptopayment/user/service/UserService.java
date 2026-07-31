package com.cryptopayment.user.service;

import com.cryptopayment.user.dto.AuthResponse;
import com.cryptopayment.user.dto.LoginRequest;
import com.cryptopayment.user.dto.RegisterRequest;
import com.cryptopayment.user.dto.UserResponse;

/**
 * Nghiệp vụ user: đăng ký, đăng nhập, truy vấn profile.
 * <p>
 * Controller phụ thuộc interface này, không phụ thuộc implementation cụ thể.
 * Chữ ký method chỉ dùng DTO — entity {@code User} không lọt ra ngoài tầng service,
 * nên tầng web không bị phụ thuộc vào chi tiết persistence.
 */
public interface UserService {

    /**
     * Đăng ký tài khoản mới, ghi event {@code UserRegistered} và cấp JWT ngay
     * (client không phải login lại).
     *
     * @throws com.cryptopayment.common.core.exception.BusinessException
     *         {@code CONFLICT} nếu username hoặc email đã tồn tại
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Xác thực username/password và cấp JWT.
     *
     * @throws com.cryptopayment.common.core.exception.BusinessException
     *         {@code UNAUTHORIZED} nếu sai tài khoản/mật khẩu,
     *         {@code FORBIDDEN} nếu tài khoản không ở trạng thái ACTIVE
     */
    AuthResponse login(LoginRequest request);

    /**
     * Lấy profile theo id (dùng cho {@code GET /api/users/me} sau khi verify JWT).
     *
     * @param userId id dạng chuỗi UUID, lấy từ subject của JWT
     * @throws com.cryptopayment.common.core.exception.BusinessException
     *         {@code UNAUTHORIZED} nếu userId không phải UUID hợp lệ,
     *         {@code NOT_FOUND} nếu không có user tương ứng
     */
    UserResponse getById(String userId);
}
