package github.lms.lemuel.user.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * User JPA Entity (인프라 레이어, 도메인과 분리)
 * DB 스키마: id, email, password, role, name, phone_number, is_active, created_at, updated_at
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String role;

    @Column(length = 100)
    private String name;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "membership_status", nullable = false, length = 20)
    private String membershipStatus;

    /** 연속 로그인 실패 횟수 — 성공 시 0 으로 초기화된다. */
    @Column(name = "failed_login_attempts", nullable = false)
    private Integer failedLoginAttempts;

    /** 기한부 잠금 해제 시각. NULL 이거나 과거면 잠기지 않은 상태. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** 마지막 비밀번호 변경 시각 — 사용 기한(기본 90 일) 판정 기준. */
    @Column(name = "password_changed_at", nullable = false)
    private LocalDateTime passwordChangedAt;

    /**
     * 마지막 로그인 성공 시각. NULL 은 "한 번도 로그인하지 않았거나 기록 이전 계정"이다 —
     * 기본값을 주지 않는 이유가 여기 있다. NOW() 로 채우면 마이그레이션이 도는 순간 전 계정이
     * "방금 쓴 계정"이 되어, 미사용 관리자 계정 조회가 영구히 빈 결과를 돌려준다.
     */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (role == null) {
            role = "USER";
        }
        if (active == null) {
            active = true;
        }
        if (membershipStatus == null) {
            membershipStatus = "APPROVED";
        }
        if (failedLoginAttempts == null) {
            failedLoginAttempts = 0;
        }
        if (passwordChangedAt == null) {
            passwordChangedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
