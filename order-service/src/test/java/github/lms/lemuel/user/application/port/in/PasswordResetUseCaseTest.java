package github.lms.lemuel.user.application.port.in;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import github.lms.lemuel.user.application.port.in.PasswordResetUseCase.ResetPasswordCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetUseCaseTest {

    @Test @DisplayName("ResetPasswordCommand - 유효한 값 보존")
    void command_valid() {
        ResetPasswordCommand cmd = new ResetPasswordCommand("token-123", "password123");
        assertThat(cmd.token()).isEqualTo("token-123");
        assertThat(cmd.newPassword()).isEqualTo("password123");
    }

    @Test @DisplayName("ResetPasswordCommand - 토큰이 비면 예외")
    void command_blankToken() {
        assertThatThrownBy(() -> new ResetPasswordCommand("  ", "password123"))
                .isInstanceOf(UserInvariantViolationException.class);
        assertThatThrownBy(() -> new ResetPasswordCommand(null, "password123"))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("ResetPasswordCommand - 비밀번호가 8자 미만이면 예외")
    void command_shortPassword() {
        assertThatThrownBy(() -> new ResetPasswordCommand("token-123", "short"))
                .isInstanceOf(UserInvariantViolationException.class);
        assertThatThrownBy(() -> new ResetPasswordCommand("token-123", null))
                .isInstanceOf(UserInvariantViolationException.class);
    }
}
