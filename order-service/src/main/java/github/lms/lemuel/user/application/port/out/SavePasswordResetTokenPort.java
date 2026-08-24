package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.PasswordResetToken;

import java.util.Optional;

public interface SavePasswordResetTokenPort {

    PasswordResetToken save(PasswordResetToken token);

    Optional<PasswordResetToken> findByToken(String token);

    Optional<PasswordResetToken> findValidTokenByUserId(Long userId);

    void deleteExpiredTokens();
}
