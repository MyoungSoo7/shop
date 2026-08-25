package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.UnlockAccountUseCase.UnlockResult;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.LoginSecurity;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 잠금 해제 서비스 단위 테스트.
 *
 * <p>잠금은 무차별 대입 대응이므로, 여기서 지켜야 할 것은 "푸는가"가 아니라 <b>어떤 경우에
 * 거부하는가</b>와 <b>무엇을 감사에 남기는가</b>다. 자기 자신 해제가 열리면 잠금은 그 계정에
 * 대해 존재하지 않는 것과 같고, 해제 직전 상태를 안 남기면 조사할 사건과 헛클릭이 같아 보인다.
 */
@ExtendWith(MockitoExtension.class)
class UnlockAccountServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 14, 0);
    private static final Clock FIXED = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;

    UnlockAccountService service;

    @BeforeEach
    void setUp() {
        service = new UnlockAccountService(loadUserPort, saveUserPort, FIXED);
    }

    private static User lockedUser(Long id, LocalDateTime lockedUntil, int failedAttempts) {
        return User.rehydrate(id, "admin@x.com", "hash", UserRole.ADMIN, "관리자", null,
                true, MembershipStatus.APPROVED,
                LoginSecurity.restore(failedAttempts, lockedUntil, NOW.minusDays(3), NOW.minusDays(1)),
                NOW.minusYears(1), NOW.minusDays(1));
    }

    @Test
    @DisplayName("잠금과 실패 카운터를 함께 지운다 — 카운터가 남으면 다음 실패 한 번에 즉시 재잠금된다")
    void clearsLockAndCounter() {
        User target = lockedUser(42L, NOW.plusMinutes(20), 5);
        when(loadUserPort.findById(42L)).thenReturn(Optional.of(target));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnlockResult result = service.unlock(42L, "오타로 잠김, 본인 확인 완료", 7L);

        assertThat(result.user().isLocked(NOW)).isFalse();
        assertThat(result.user().lockedUntil()).isNull();
        assertThat(result.user().failedLoginAttempts()).isZero();
        verify(saveUserPort).save(target);
    }

    @Test
    @DisplayName("해제 직전 상태를 함께 돌려준다 — '5회 실패로 잠겨 있었다'와 '헛클릭'은 다른 사건이다")
    void reportsPreviousState() {
        LocalDateTime lockedUntil = NOW.plusMinutes(20);
        when(loadUserPort.findById(42L)).thenReturn(Optional.of(lockedUser(42L, lockedUntil, 5)));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnlockResult result = service.unlock(42L, "본인 확인 완료", 7L);

        assertThat(result.wasLocked()).isTrue();
        assertThat(result.previousLockedUntil()).isEqualTo(lockedUntil);
        assertThat(result.previousFailedAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("이미 만료된 잠금에도 성공한다(멱등) — 다만 wasLocked=false 로 구분해 남긴다")
    void idempotentOnExpiredLock() {
        when(loadUserPort.findById(42L)).thenReturn(Optional.of(lockedUser(42L, NOW.minusMinutes(1), 5)));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnlockResult result = service.unlock(42L, "화면에서 잠겨 보였음", 7L);

        assertThat(result.wasLocked()).isFalse();
        assertThat(result.previousFailedAttempts()).isEqualTo(5);
        assertThat(result.user().failedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("잠금 해제는 비밀번호 기준 시각과 마지막 로그인 시각을 건드리지 않는다")
    void leavesCredentialTimestampsAlone() {
        when(loadUserPort.findById(42L)).thenReturn(Optional.of(lockedUser(42L, NOW.plusMinutes(20), 5)));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnlockResult result = service.unlock(42L, "본인 확인 완료", 7L);

        assertThat(result.user().getLoginSecurity().getPasswordChangedAt()).isEqualTo(NOW.minusDays(3));
        assertThat(result.user().lastLoginAt()).isEqualTo(NOW.minusDays(1));
    }

    @Test
    @DisplayName("사유 없이는 거부한다 — 공격 대응을 되돌리는 조작은 설명돼야 한다")
    void requiresReason() {
        assertThatThrownBy(() -> service.unlock(42L, "  ", 7L))
                .isInstanceOf(UserInvariantViolationException.class);

        verifyNoInteractions(loadUserPort, saveUserPort);
    }

    @Test
    @DisplayName("자기 자신은 풀 수 없다 — 스스로 풀 수 있으면 그 계정에 잠금은 없는 것과 같다")
    void cannotUnlockSelf() {
        assertThatThrownBy(() -> service.unlock(7L, "내 계정이 잠겼다", 7L))
                .isInstanceOf(UserInvariantViolationException.class);

        verifyNoInteractions(loadUserPort);
        verify(saveUserPort, never()).save(any());
    }

    @Test
    @DisplayName("없는 계정이면 UserNotFound")
    void unknownUser() {
        when(loadUserPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlock(99L, "사유", 7L))
                .isInstanceOf(UserNotFoundException.class);

        verify(saveUserPort, never()).save(any());
    }
}
