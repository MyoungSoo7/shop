package github.lms.lemuel.user.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SpringDataPasswordResetTokenRepository extends JpaRepository<PasswordResetTokenJpaEntity, Long> {

    Optional<PasswordResetTokenJpaEntity> findByToken(String token);

    Optional<PasswordResetTokenJpaEntity> findByUserIdAndUsedFalseAndExpiryDateAfter(
            Long userId, LocalDateTime currentTime);

    void deleteByExpiryDateBefore(LocalDateTime expiryDate);
}
