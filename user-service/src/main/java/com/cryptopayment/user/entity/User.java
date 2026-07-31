package com.cryptopayment.user.entity;

import java.time.Instant;
import java.util.UUID;

import com.cryptopayment.common.core.domain.BaseEntity;

import com.cryptopayment.user.common.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Tài khoản người dùng — map bảng user_db.users.
 * <p>
 * Kế thừa {@link BaseEntity} để có created_at/updated_at tự set. KHÔNG dùng
 * @Data/@EqualsAndHashCode của Lombok (theo lưu ý ở BaseEntity) — equals/hashCode
 * tự viết dựa trên id để an toàn với JPA (Set/Map/merge).
 * <p>
 * CỐ Ý KHÔNG có {@code @Setter}: id do Hibernate sinh, passwordHash phải luôn là
 * BCrypt hash — mở setter cho mọi field cho phép code gọi lách qua các ràng buộc đó
 * (ví dụ set passwordHash = plaintext). Thay vào đó dùng factory
 * {@link #createActive} và các method nghiệp vụ ({@link #suspend()},
 * {@link #markDeleted()}) để trạng thái chỉ đổi theo đúng luật.
 * <p>
 * id là UUID sinh bởi Hibernate (GenerationType.UUID) — client không đoán được id
 * tuần tự, phù hợp hệ phân tán (không phụ thuộc sequence tập trung).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA cần no-arg; code nghiệp vụ dùng factory
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    /** BCrypt hash — KHÔNG bao giờ lưu plaintext. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    /** Thời điểm soft-delete (null nếu chưa bị xóa). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Tạo user mới ở trạng thái ACTIVE.
     *
     * @param passwordHash BCrypt hash — caller PHẢI hash trước, entity không tự hash
     *                     để domain không phụ thuộc vào PasswordEncoder của Spring Security
     */
    public static User createActive(String username, String email, String passwordHash, String fullName) {
        User user = new User();
        user.username = username;
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    /** Tạm khóa tài khoản — user không đăng nhập được nhưng dữ liệu vẫn còn. */
    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    /** Soft-delete: đánh dấu DELETED + ghi mốc thời gian, KHÔNG xóa cứng bản ghi. */
    public void markDeleted() {
        this.status = UserStatus.DELETED;
        this.deletedAt = Instant.now();
    }

    /** Chỉ tài khoản ACTIVE mới được phép đăng nhập. */
    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        // Hằng số theo class — an toàn khi id chưa gán (entity mới), tránh
        // thay đổi hashCode sau khi persist (yêu cầu của JPA entity trong HashSet).
        return getClass().hashCode();
    }
}
