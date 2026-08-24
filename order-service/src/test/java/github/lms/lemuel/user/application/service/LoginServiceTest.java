package github.lms.lemuel.user.application.service;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.LoginSecurityPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;
    @Mock PasswordHashPort passwordHashPort;
    @Mock TokenProviderPort tokenProviderPort;
    LoginService service;

    // 생성자가 둘(설정 바인딩용 / 정책 주입용)이라 @InjectMocks 는 어느 쪽을 쓸지 정하지 못한다 —
    // 잠금 정책이 이 테스트의 관심사가 아니므로 기본 정책을 명시적으로 넘긴다.
    @BeforeEach
    void setUp() {
        service = new LoginService(loadUserPort, new LoginAttemptRecorder(saveUserPort),
                passwordHashPort, tokenProviderPort, Clock.system(ZoneId.of("Asia/Seoul")),
                new LoginSecurityPolicy(5, Duration.ofMinutes(30), Duration.ofDays(90)));
    }

    private User user() {
        return new User(1L, "u@example.com", "hashed", UserRole.USER, null, null);
    }

    @Test @DisplayName("정상 로그인 - 토큰 반환")
    void login_success() {
        when(loadUserPort.findByEmail("u@example.com")).thenReturn(Optional.of(user()));
        when(passwordHashPort.matches("raw", "hashed")).thenReturn(true);
        when(tokenProviderPort.generateToken("u@example.com", "USER", 1L)).thenReturn("jwt-token");

        LoginUseCase.LoginResult result = service.login(
                new LoginUseCase.LoginCommand("u@example.com", "raw"));

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.email()).isEqualTo("u@example.com");
        assertThat(result.role()).isEqualTo("USER");
    }

    @Test @DisplayName("사용자 미존재 - InvalidCredentialsException")
    void login_userNotFound() {
        when(loadUserPort.findByEmail("x@y.z")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                new LoginUseCase.LoginCommand("x@y.z", "raw")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test @DisplayName("비밀번호 불일치 - InvalidCredentialsException")
    void login_wrongPassword() {
        when(loadUserPort.findByEmail("u@example.com")).thenReturn(Optional.of(user()));
        when(passwordHashPort.matches("bad", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new LoginUseCase.LoginCommand("u@example.com", "bad")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test @DisplayName("Command 검증: email 공백")
    void command_blankEmail() {
        assertThatThrownBy(() -> new LoginUseCase.LoginCommand("  ", "pw"))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("Command 검증: password 공백")
    void command_blankPassword() {
        assertThatThrownBy(() -> new LoginUseCase.LoginCommand("u@example.com", ""))
                .isInstanceOf(UserInvariantViolationException.class);
    }
}
