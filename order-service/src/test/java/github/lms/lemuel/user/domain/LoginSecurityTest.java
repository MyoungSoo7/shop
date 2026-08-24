package github.lms.lemuel.user.domain;

import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로그인 보안 상태 전이 — 잠금 임계·기한·비밀번호 만료.
 *
 * <p>시각을 인자로 받는 순수 도메인이라 "30 분 뒤"를 실제로 기다리지 않고 검증할 수 있다.
 */
@DisplayName("LoginSecurity — 무차별 대입 잠금과 비밀번호 사용 기한")
class LoginSecurityTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 21, 10, 0);
    private static final LoginSecurityPolicy POLICY =
            new LoginSecurityPolicy(5, Duration.ofMinutes(30), Duration.ofDays(90));

    @Nested
    @DisplayName("잠금")
    class Lock {

        @Test
        @DisplayName("임계 직전(4회)까지는 잠기지 않는다")
        void notLockedBeforeThreshold() {
            LoginSecurity security = LoginSecurity.initial(T0);
            for (int i = 0; i < 4; i++) {
                security = security.afterFailure(POLICY, T0);
            }

            assertThat(security.getFailedAttempts()).isEqualTo(4);
            assertThat(security.isLockedAt(T0)).isFalse();
        }

        @Test
        @DisplayName("임계(5회) 도달 시 잠금 만료 시각이 찍힌다")
        void locksAtThreshold() {
            LoginSecurity security = LoginSecurity.initial(T0);
            for (int i = 0; i < 5; i++) {
                security = security.afterFailure(POLICY, T0);
            }

            assertThat(security.isLockedAt(T0)).isTrue();
            assertThat(security.getLockedUntil()).isEqualTo(T0.plusMinutes(30));
        }

        @Test
        @DisplayName("잠금 만료 정각은 이미 풀린 것으로 본다 — 경계는 사용자에게 유리하게")
        void unlockBoundaryIsInclusive() {
            LoginSecurity security = LoginSecurity.initial(T0);
            for (int i = 0; i < 5; i++) {
                security = security.afterFailure(POLICY, T0);
            }

            assertThat(security.isLockedAt(T0.plusMinutes(29))).isTrue();
            assertThat(security.isLockedAt(T0.plusMinutes(30))).isFalse();
        }

        @Test
        @DisplayName("잠긴 뒤에도 시도 횟수는 계속 오른다 — 5 에서 멈추면 500 회 두드린 공격이 안 보인다")
        void keepsCountingAfterLock() {
            LoginSecurity security = LoginSecurity.initial(T0);
            for (int i = 0; i < 8; i++) {
                security = security.afterFailure(POLICY, T0);
            }

            assertThat(security.getFailedAttempts()).isEqualTo(8);
        }

        @Test
        @DisplayName("성공 한 번이 과거 실패와 잠금을 모두 지운다")
        void successClearsEverything() {
            LoginSecurity security = LoginSecurity.initial(T0);
            for (int i = 0; i < 5; i++) {
                security = security.afterFailure(POLICY, T0);
            }

            LoginSecurity cleared = security.afterSuccess();

            assertThat(cleared.getFailedAttempts()).isZero();
            assertThat(cleared.getLockedUntil()).isNull();
            assertThat(cleared.isLockedAt(T0)).isFalse();
        }

        @Test
        @DisplayName("비밀번호 변경은 잠금도 함께 푼다 — 재설정하고도 못 들어오면 재설정이 무의미하다")
        void passwordChangeUnlocks() {
            LoginSecurity security = LoginSecurity.initial(T0);
            for (int i = 0; i < 5; i++) {
                security = security.afterFailure(POLICY, T0);
            }

            LoginSecurity reset = security.afterPasswordChange(T0.plusMinutes(1));

            assertThat(reset.isLockedAt(T0.plusMinutes(1))).isFalse();
            assertThat(reset.getFailedAttempts()).isZero();
            assertThat(reset.getPasswordChangedAt()).isEqualTo(T0.plusMinutes(1));
        }
    }

    @Nested
    @DisplayName("비밀번호 사용 기한")
    class PasswordAge {

        @Test
        @DisplayName("90일 정각은 아직 유효, 하루 더 지나면 만료")
        void expiresAfterMaxAge() {
            LoginSecurity security = LoginSecurity.initial(T0);

            assertThat(security.passwordExpiredAt(POLICY, T0.plusDays(90))).isFalse();
            assertThat(security.passwordExpiredAt(POLICY, T0.plusDays(91))).isTrue();
        }

        @Test
        @DisplayName("기준 시각을 모르는 계정은 만료로 보지 않는다 — 마이그레이션 순간 전원 잠김 방지")
        void unknownBaselineNeverExpires() {
            LoginSecurity security = LoginSecurity.restore(0, null, null);

            assertThat(security.passwordExpiredAt(POLICY, T0.plusYears(5))).isFalse();
        }

        @Test
        @DisplayName("사용 기한 0 이면 만료 검사를 하지 않는다")
        void zeroMaxAgeDisablesCheck() {
            LoginSecurityPolicy noExpiry = new LoginSecurityPolicy(5, Duration.ofMinutes(30), Duration.ZERO);
            LoginSecurity security = LoginSecurity.initial(T0);

            assertThat(noExpiry.checksPasswordAge()).isFalse();
            assertThat(security.passwordExpiredAt(noExpiry, T0.plusYears(10))).isFalse();
        }
    }

    @Nested
    @DisplayName("정책 불변식")
    class Policy {

        @Test
        @DisplayName("임계 0 회는 로그인 자체를 불가능하게 만든다 — 거부")
        void rejectsZeroThreshold() {
            assertThatThrownBy(() -> new LoginSecurityPolicy(0, Duration.ofMinutes(30), Duration.ofDays(90)))
                    .isInstanceOf(UserInvariantViolationException.class);
        }

        @Test
        @DisplayName("잠금 시간 0 은 잠금이 즉시 풀려 무의미하다 — 거부")
        void rejectsZeroLockDuration() {
            assertThatThrownBy(() -> new LoginSecurityPolicy(5, Duration.ZERO, Duration.ofDays(90)))
                    .isInstanceOf(UserInvariantViolationException.class);
        }

        @Test
        @DisplayName("음수 사용 기한은 즉시 전원 만료를 뜻한다 — 거부")
        void rejectsNegativePasswordAge() {
            assertThatThrownBy(() -> new LoginSecurityPolicy(5, Duration.ofMinutes(30), Duration.ofDays(-1)))
                    .isInstanceOf(UserInvariantViolationException.class);
        }
    }
}
