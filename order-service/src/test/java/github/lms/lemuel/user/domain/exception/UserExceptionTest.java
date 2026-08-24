package github.lms.lemuel.user.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class UserExceptionTest {

    @Test @DisplayName("DuplicateEmailException: 이메일이 메시지에 포함된다")
    void duplicateEmail_message() {
        var ex = new DuplicateEmailException("test@example.com");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("이미 존재하는 이메일입니다: test@example.com");
    }

    @Test @DisplayName("DuplicateEmailException: cause가 올바르게 전달된다")
    void duplicateEmail_withCause() {
        var cause = new RuntimeException("DB constraint");
        var ex = new DuplicateEmailException("dup@test.com", cause);
        assertThat(ex.getMessage()).isEqualTo("이미 존재하는 이메일입니다: dup@test.com");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test @DisplayName("InvalidCredentialsException: 기본 생성자")
    void invalidCredentials_default() {
        var ex = new InvalidCredentialsException();
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Invalid email or password");
    }

    @Test @DisplayName("InvalidCredentialsException: 문자열 생성자")
    void invalidCredentials_custom() {
        var ex = new InvalidCredentialsException("비밀번호가 일치하지 않습니다");
        assertThat(ex.getMessage()).isEqualTo("비밀번호가 일치하지 않습니다");
    }

    @Test @DisplayName("InvalidPasswordResetTokenException: 기본 생성자")
    void invalidPasswordResetToken_default() {
        var ex = new InvalidPasswordResetTokenException();
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("유효하지 않거나 만료된 비밀번호 재설정 토큰입니다.");
    }

    @Test @DisplayName("InvalidPasswordResetTokenException: 문자열 생성자")
    void invalidPasswordResetToken_custom() {
        var ex = new InvalidPasswordResetTokenException("토큰이 만료되었습니다");
        assertThat(ex.getMessage()).isEqualTo("토큰이 만료되었습니다");
    }

    @Test @DisplayName("UserNotFoundException: 문자열 생성자")
    void userNotFound_stringConstructor() {
        var ex = new UserNotFoundException("사용자를 찾을 수 없습니다");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("사용자를 찾을 수 없습니다");
    }

    @Test @DisplayName("UserNotFoundException: Long 생성자")
    void userNotFound_longConstructor() {
        var ex = new UserNotFoundException(42L);
        assertThat(ex.getMessage()).isEqualTo("User not found with id: 42");
    }
}
