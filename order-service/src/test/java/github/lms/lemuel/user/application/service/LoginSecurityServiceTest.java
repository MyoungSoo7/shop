package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.LoginSecurity;
import github.lms.lemuel.user.domain.LoginSecurityPolicy;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.AccountLockedException;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import github.lms.lemuel.user.domain.exception.PasswordExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그인 잠금·비밀번호 기한이 <b>서비스 흐름에서</b> 실제로 걸리는지.
 *
 * <p>도메인 규칙이 옳아도 호출 순서가 틀리면 무력해진다 — 이 테스트가 지키는 것은 순서다:
 * 잠금 검사가 비밀번호 대조보다 앞이고, 기한 검사는 대조 뒤이며, 실패는 예외 전에 저장된다.
 */
@DisplayName("LoginService — 무차별 대입 잠금 · 비밀번호 사용 기한")
class LoginSecurityServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);
    private static final LoginSecurityPolicy POLICY =
            new LoginSecurityPolicy(5, Duration.ofMinutes(30), Duration.ofDays(90));

    private LoadUserPort loadUserPort;
    private SaveUserPort saveUserPort;
    private PasswordHashPort passwordHashPort;
    private TokenProviderPort tokenProviderPort;
    private LoginService service;

    @BeforeEach
    void setUp() {
        loadUserPort = mock(LoadUserPort.class);
        saveUserPort = mock(SaveUserPort.class);
        passwordHashPort = mock(PasswordHashPort.class);
        tokenProviderPort = mock(TokenProviderPort.class);
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        service = new LoginService(loadUserPort, new LoginAttemptRecorder(saveUserPort),
                passwordHashPort, tokenProviderPort, fixed, POLICY);
    }

    private User user(LoginSecurity security) {
        return User.rehydrate(1L, "buyer@lemuel.io", "$2a$12$hash", UserRole.USER,
                "구매자", null, true, MembershipStatus.APPROVED, security,
                NOW.minusDays(200), NOW.minusDays(200));
    }

    private LoginUseCase.LoginCommand command() {
        return new LoginUseCase.LoginCommand("buyer@lemuel.io", "password123");
    }

    @Test
    @DisplayName("정상 로그인은 토큰을 발급하고 실패 누적을 지운다")
    void success_clearsFailures() {
        User u = user(LoginSecurity.restore(3, null, NOW.minusDays(1)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches(anyString(), anyString())).thenReturn(true);
        when(tokenProviderPort.generateToken(anyString(), anyString(), anyLong())).thenReturn("jwt");

        LoginUseCase.LoginResult result = service.login(command());

        assertThat(result.token()).isEqualTo("jwt");
        assertThat(u.getLoginSecurity().getFailedAttempts()).isZero();
        verify(saveUserPort).save(u);
    }

    @Test
    @DisplayName("비밀번호 실패는 예외를 던지기 전에 저장된다 — 저장을 미루면 카운터가 영원히 0")
    void failure_isPersistedBeforeThrowing() {
        User u = user(LoginSecurity.initial(NOW.minusDays(1)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(saveUserPort).save(u);
        assertThat(u.getLoginSecurity().getFailedAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("5번째 실패에서 잠금 — 401 이 아니라 423 계열(AccountLocked)로 알린다")
    void fifthFailure_locksAccount() {
        User u = user(LoginSecurity.restore(4, null, NOW.minusDays(1)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(AccountLockedException.class);

        assertThat(u.isLocked(NOW)).isTrue();
    }

    @Test
    @DisplayName("잠긴 계정은 올바른 비밀번호로도 들어오지 못한다 — 대조 자체를 하지 않는다")
    void lockedAccount_skipsPasswordCheck() {
        User u = user(LoginSecurity.restore(5, NOW.plusMinutes(10), NOW.minusDays(1)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(AccountLockedException.class);

        verify(passwordHashPort, never()).matches(anyString(), anyString());
        verify(saveUserPort, never()).save(any());
    }

    @Test
    @DisplayName("잠금 기한이 지나면 스스로 풀린다 — 관리자 개입 없이 재로그인 가능")
    void lockExpires() {
        User u = user(LoginSecurity.restore(5, NOW.minusMinutes(1), NOW.minusDays(1)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches(anyString(), anyString())).thenReturn(true);
        when(tokenProviderPort.generateToken(anyString(), anyString(), anyLong())).thenReturn("jwt");

        assertThat(service.login(command()).token()).isEqualTo("jwt");
    }

    @Test
    @DisplayName("비밀번호 기한 초과는 대조 성공 뒤에 판정한다 — 계정 열거 통로를 만들지 않는다")
    void passwordExpired_afterSuccessfulMatch() {
        User u = user(LoginSecurity.restore(0, null, NOW.minusDays(91)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(PasswordExpiredException.class);

        verify(passwordHashPort).matches(anyString(), anyString()); // 대조는 했다
        verify(tokenProviderPort, never()).generateToken(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("기한이 지났어도 비밀번호가 틀리면 '만료'가 아니라 '자격 실패'로 답한다")
    void expiredButWrongPassword_reportsCredentialFailure() {
        User u = user(LoginSecurity.restore(0, null, NOW.minusDays(91)));
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.of(u));
        when(passwordHashPort.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("없는 계정은 잠금 기록 없이 자격 실패로만 답한다")
    void unknownEmail_noStateWritten() {
        when(loadUserPort.findByEmail("buyer@lemuel.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(saveUserPort, never()).save(any());
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 잠금이 풀리고 사용 기한이 새로 시작한다")
    void passwordChange_unlocksAndRefreshesAge() {
        User u = user(LoginSecurity.restore(5, NOW.plusMinutes(10), NOW.minusDays(200)));

        u.updatePassword("$2a$12$newhash");

        assertThat(u.isLocked(NOW)).isFalse();
        assertThat(u.isPasswordExpired(POLICY, NOW)).isFalse();
        assertThat(u.getLoginSecurity().getFailedAttempts()).isZero();
    }
}
