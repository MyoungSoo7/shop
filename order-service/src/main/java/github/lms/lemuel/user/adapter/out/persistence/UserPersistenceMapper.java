package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.LoginSecurity;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Domain <-> JpaEntity 수동 매핑.
 *
 * <p>User 도메인이 불변식을 강제하는 봉인 객체(@Setter 없음)라 MapStruct 대신 수동 매핑을 유지한다
 * (PaymentMapper 와 동형). 복원은 {@link User#rehydrate} 로 DB 값(membershipStatus 포함)을 그대로 재구성한다.
 */
@Component
public class UserPersistenceMapper {

    public User toDomain(UserJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        boolean active = entity.getActive() == null || entity.getActive();
        return User.rehydrate(
                entity.getId(),
                entity.getEmail(),
                entity.getPassword(),
                UserRole.fromString(entity.getRole()),
                entity.getName(),
                entity.getPhoneNumber(),
                active,
                MembershipStatus.fromString(entity.getMembershipStatus()),
                LoginSecurity.restore(
                        entity.getFailedLoginAttempts() == null ? 0 : entity.getFailedLoginAttempts(),
                        entity.getLockedUntil(),
                        entity.getPasswordChangedAt()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public UserJpaEntity toEntity(User domain) {
        if (domain == null) {
            return null;
        }
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(domain.getId());
        entity.setEmail(domain.getEmail());
        entity.setPassword(domain.getPasswordHash());
        entity.setRole(domain.getRole().name());
        entity.setName(domain.getName());
        entity.setPhoneNumber(domain.getPhoneNumber());
        entity.setActive(domain.isActive());
        entity.setMembershipStatus(
                domain.getMembershipStatus() == null ? "APPROVED" : domain.getMembershipStatus().name());
        LoginSecurity security = domain.getLoginSecurity();
        entity.setFailedLoginAttempts(security == null ? 0 : security.getFailedAttempts());
        entity.setLockedUntil(security == null ? null : security.getLockedUntil());
        // password_changed_at 은 NOT NULL 이고 merge 경로에서는 @PrePersist 가 돌지 않는다 —
        // 기준 시각을 모르는 도메인(레거시 복원)을 그대로 흘리면 UPDATE 가 제약 위반으로 터진다.
        LocalDateTime passwordChangedAt = security == null ? null : security.getPasswordChangedAt();
        entity.setPasswordChangedAt(passwordChangedAt != null ? passwordChangedAt
                : (domain.getCreatedAt() != null ? domain.getCreatedAt() : LocalDateTime.now()));
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
