package com.cryptopayment.user.service.impl;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cryptopayment.common.core.exception.BusinessException;
import com.cryptopayment.common.core.exception.ErrorCode;
import com.cryptopayment.common.security.jwt.JwtService;
import com.cryptopayment.user.entity.User;
import com.cryptopayment.user.dto.AuthResponse;
import com.cryptopayment.user.dto.LoginRequest;
import com.cryptopayment.user.dto.RegisterRequest;
import com.cryptopayment.user.dto.UserResponse;
import com.cryptopayment.user.event.UserEventRecorder;
import com.cryptopayment.user.repository.UserRepository;
import com.cryptopayment.user.service.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation của {@link UserService}.
 * <p>
 * Chỉ giữ nghiệp vụ user. Việc ghi/serialize domain event đã tách sang
 * {@link UserEventRecorder} (SRP) — class này không biết event lưu ở outbox table
 * hay đẩy qua CDC, cũng không biết format JSON.
 * <p>
 * Điểm cốt lõi (UC1): {@link #register} ghi {@code users} + outbox trong CÙNG 1
 * transaction atomic → event {@code UserRegistered} không bao giờ mất kể cả khi
 * Kafka down; {@code OutboxPublisherJob} đẩy lên Kafka bất đồng bộ sau đó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** Dùng chung cho mọi lỗi đăng nhập để không lộ username có tồn tại hay không. */
    private static final String INVALID_CREDENTIALS = "Sai tài khoản hoặc mật khẩu";

    private final UserRepository userRepository;
    private final UserEventRecorder userEventRecorder;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        requireUsernameAvailable(request.username());
        requireEmailAvailable(request.email());

        User user = userRepository.save(User.createActive(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName()));

        userEventRecorder.recordUserRegistered(user);

        log.info("Đăng ký thành công: userId={} username={}", user.getId(), user.getUsername());
        return issueToken(user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tài khoản không ở trạng thái hoạt động");
        }

        log.info("Đăng nhập thành công: userId={} username={}", user.getId(), user.getUsername());
        return issueToken(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(String userId) {
        User user = userRepository.findById(parseUserId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Không tìm thấy user"));
        return UserResponse.from(user);
    }

    // ---------- helper ----------

    private void requireUsernameAvailable(String username) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Username đã tồn tại");
        }
    }

    private void requireEmailAvailable(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Email đã tồn tại");
        }
    }

    /** Cấp JWT + gói response — dùng chung cho register và login, tránh lặp. */
    private AuthResponse issueToken(User user) {
        String token = jwtService.generateToken(user.getId().toString(), user.getUsername());
        return AuthResponse.of(token, UserResponse.from(user));
    }

    /**
     * userId đến từ subject của JWT. Nếu không phải UUID hợp lệ thì token bất thường
     * → trả 401, KHÔNG để IllegalArgumentException của UUID.fromString rơi thành 500.
     */
    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException ex) {
            log.warn("userId trong token không phải UUID hợp lệ: {}", userId);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token không hợp lệ");
        }
    }
}
