package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.PasswordResetUseCase;
import github.lms.lemuel.user.application.port.out.*;
import github.lms.lemuel.user.domain.PasswordResetToken;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidPasswordResetTokenException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;
    @Mock PasswordHashPort passwordHashPort;
    @Mock SavePasswordResetTokenPort savePasswordResetTokenPort;
    @Mock SendEmailPort sendEmailPort;

    /** 토큰 만료 판정을 결정적으로 만들기 위한 고정 시각(KST) — 프로덕션은 TimeConfig 의 시스템 Clock. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 12, 0);
    private final Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService(loadUserPort, saveUserPort,
                savePasswordResetTokenPort, sendEmailPort, passwordHashPort, clock);
    }

    private User user(Long id, String email) {
        User u = User.createWithRole(email, "oldhash", UserRole.USER);
        u.assignId(id);
        return u;
    }

    private PasswordResetToken validToken(Long userId, String tokenValue) {
        return new PasswordResetToken(null, userId, tokenValue,
                NOW.plusMinutes(30), false, NOW);
    }

    @Test @DisplayName("비밀번호 재설정 요청 - 존재하지 않는 이메일이면 조용히 무시")
    void requestReset_unknownEmail_silentlyIgnored() {
        when(loadUserPort.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        // 예외 없이 조용히 처리되어야 함 (보안: 이메일 존재 여부 노출 방지)
        assertThatCode(() -> passwordResetService.requestPasswordReset("unknown@test.com"))
                .doesNotThrowAnyException();
        verify(savePasswordResetTokenPort, never()).save(any());
        verify(sendEmailPort, never()).sendPasswordResetEmail(any(), any());
    }

    @Test @DisplayName("비밀번호 재설정 요청 - 신규 토큰 생성 후 이메일 발송")
    void requestReset_newToken() {
        User u = user(1L, "u@test.com");
        when(loadUserPort.findByEmail("u@test.com")).thenReturn(Optional.of(u));
        when(savePasswordResetTokenPort.findValidTokenByUserId(1L)).thenReturn(Optional.empty());
        when(savePasswordResetTokenPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        passwordResetService.requestPasswordReset("u@test.com");

        verify(savePasswordResetTokenPort).save(any(PasswordResetToken.class));
        verify(sendEmailPort).sendPasswordResetEmail(eq("u@test.com"), anyString());
    }

    @Test @DisplayName("비밀번호 재설정 요청 - 기존 유효 토큰이 있으면 재사용(신규 저장 없음)")
    void requestReset_reuseExistingToken() {
        User u = user(1L, "u@test.com");
        PasswordResetToken existing = validToken(1L, "existing-token-1234");
        when(loadUserPort.findByEmail("u@test.com")).thenReturn(Optional.of(u));
        when(savePasswordResetTokenPort.findValidTokenByUserId(1L)).thenReturn(Optional.of(existing));

        passwordResetService.requestPasswordReset("u@test.com");

        verify(savePasswordResetTokenPort, never()).save(any());
        verify(sendEmailPort).sendPasswordResetEmail("u@test.com", "existing-token-1234");
    }

    @Test @DisplayName("비밀번호 재설정 요청 - 이메일 발송 실패해도 예외를 삼킨다")
    void requestReset_emailFailureSwallowed() {
        User u = user(1L, "u@test.com");
        PasswordResetToken existing = validToken(1L, "existing-token-1234");
        when(loadUserPort.findByEmail("u@test.com")).thenReturn(Optional.of(u));
        when(savePasswordResetTokenPort.findValidTokenByUserId(1L)).thenReturn(Optional.of(existing));
        doThrow(new RuntimeException("smtp down"))
                .when(sendEmailPort).sendPasswordResetEmail(any(), any());

        assertThatCode(() -> passwordResetService.requestPasswordReset("u@test.com"))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("resetPassword - 유효 토큰이면 비밀번호 갱신 및 토큰 사용처리")
    void resetPassword_success() {
        PasswordResetToken token = validToken(1L, "reset-token-12345678");
        User u = user(1L, "u@test.com");
        when(savePasswordResetTokenPort.findByToken("reset-token-12345678")).thenReturn(Optional.of(token));
        when(loadUserPort.findById(1L)).thenReturn(Optional.of(u));
        when(passwordHashPort.hash("newpassword123")).thenReturn("newhash");

        passwordResetService.resetPassword(
                new PasswordResetUseCase.ResetPasswordCommand("reset-token-12345678", "newpassword123"));

        assertThat(u.getPasswordHash()).isEqualTo("newhash");
        assertThat(token.isUsed()).isTrue();
        verify(saveUserPort).save(u);
        verify(savePasswordResetTokenPort).save(token);
    }

    @Test @DisplayName("resetPassword - 토큰이 없으면 예외")
    void resetPassword_tokenNotFound() {
        when(savePasswordResetTokenPort.findByToken("reset-token-12345678")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.resetPassword(
                new PasswordResetUseCase.ResetPasswordCommand("reset-token-12345678", "newpassword123")))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
    }

    @Test @DisplayName("resetPassword - 만료/사용된 토큰이면 예외")
    void resetPassword_invalidToken() {
        PasswordResetToken token = validToken(1L, "reset-token-12345678");
        token.markAsUsed();
        when(savePasswordResetTokenPort.findByToken("reset-token-12345678")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordResetService.resetPassword(
                new PasswordResetUseCase.ResetPasswordCommand("reset-token-12345678", "newpassword123")))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
        verify(saveUserPort, never()).save(any());
    }

    @Test @DisplayName("resetPassword - 사용자를 찾을 수 없으면 예외")
    void resetPassword_userNotFound() {
        PasswordResetToken token = validToken(9L, "reset-token-12345678");
        when(savePasswordResetTokenPort.findByToken("reset-token-12345678")).thenReturn(Optional.of(token));
        when(loadUserPort.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.resetPassword(
                new PasswordResetUseCase.ResetPasswordCommand("reset-token-12345678", "newpassword123")))
                .isInstanceOf(UserNotFoundException.class);
    }
}
