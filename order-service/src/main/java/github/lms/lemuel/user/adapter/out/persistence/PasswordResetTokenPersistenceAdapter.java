package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.application.port.out.SavePasswordResetTokenPort;
import github.lms.lemuel.user.domain.PasswordResetToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PasswordResetTokenPersistenceAdapter implements SavePasswordResetTokenPort {

    private final SpringDataPasswordResetTokenRepository repository;
    private final PasswordResetTokenMapper mapper;

    @Override
    @Transactional
    public PasswordResetToken save(PasswordResetToken token) {
        PasswordResetTokenJpaEntity entity = mapper.toEntity(token);
        PasswordResetTokenJpaEntity savedEntity = repository.save(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PasswordResetToken> findByToken(String token) {
        return repository.findByToken(token)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PasswordResetToken> findValidTokenByUserId(Long userId) {
        return repository.findByUserIdAndUsedFalseAndExpiryDateAfter(userId, LocalDateTime.now())
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public void deleteExpiredTokens() {
        repository.deleteByExpiryDateBefore(LocalDateTime.now());
    }
}
