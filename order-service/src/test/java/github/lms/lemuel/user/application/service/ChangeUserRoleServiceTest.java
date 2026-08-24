package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.ChangeUserRoleUseCase.RoleChangeResult;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 역할 변경 서비스 단위 테스트.
 *
 * <p>권한 상승 경로라 지켜야 할 것은 "성공하는가"가 아니라 <b>어떤 경우에 거부하는가</b>다.
 * 사유 없는 변경과 자기 자신 변경은 각각 감사 불가·복구 불가로 이어진다.
 */
@ExtendWith(MockitoExtension.class)
class ChangeUserRoleServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;

    ChangeUserRoleService service;

    @BeforeEach
    void setUp() {
        service = new ChangeUserRoleService(loadUserPort, saveUserPort);
    }

    private static User user(Long id, UserRole role) {
        return User.rehydrate(id, "a@b.c", "hash", role, "홍길동", "010-0000-0000",
                true, github.lms.lemuel.user.domain.MembershipStatus.APPROVED,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("역할을 바꾸고 변경 전 역할을 함께 돌려준다 — 상승인지 강등인지 감사가 판단할 수 있어야 한다")
    void changesRoleAndReportsPreviousRole() {
        User target = user(42L, UserRole.USER);
        when(loadUserPort.findById(42L)).thenReturn(Optional.of(target));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleChangeResult result = service.changeRole(42L, UserRole.MANAGER, "CS 팀 배치", 7L);

        assertThat(result.previousRole()).isEqualTo(UserRole.USER);
        assertThat(result.user().getRole()).isEqualTo(UserRole.MANAGER);
        verify(saveUserPort).save(target);
    }

    @Test
    @DisplayName("사유가 비면 거부한다 — 근거 없는 권한 변경은 감사에서 설명되지 않는다")
    void rejectsBlankReason() {
        assertThatThrownBy(() -> service.changeRole(42L, UserRole.ADMIN, "  ", 7L))
                .isInstanceOf(UserInvariantViolationException.class)
                .hasMessageContaining("사유");

        verifyNoInteractions(loadUserPort, saveUserPort);
    }

    @Test
    @DisplayName("사유가 null 이어도 거부한다")
    void rejectsNullReason() {
        assertThatThrownBy(() -> service.changeRole(42L, UserRole.ADMIN, null, 7L))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test
    @DisplayName("바꿀 역할이 없으면 거부한다")
    void rejectsNullRole() {
        assertThatThrownBy(() -> service.changeRole(42L, null, "사유", 7L))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test
    @DisplayName("자기 자신의 역할은 바꿀 수 없다 — 되돌릴 권한까지 함께 사라진다")
    void rejectsSelfChange() {
        assertThatThrownBy(() -> service.changeRole(7L, UserRole.USER, "정리", 7L))
                .isInstanceOf(UserInvariantViolationException.class)
                .hasMessageContaining("자기 자신");

        verify(loadUserPort, never()).findById(any());
    }

    @Test
    @DisplayName("조작자를 모를 때(이메일 주체 토큰)는 자기 자신 차단만 못 할 뿐 변경은 막지 않는다")
    void allowsWhenActorUnknown() {
        User target = user(42L, UserRole.USER);
        when(loadUserPort.findById(42L)).thenReturn(Optional.of(target));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.changeRole(42L, UserRole.MANAGER, "사유", null).user().getRole())
                .isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("없는 회원은 404 로 구분한다")
    void rejectsMissingUser() {
        when(loadUserPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(99L, UserRole.MANAGER, "사유", 7L))
                .isInstanceOf(UserNotFoundException.class);

        verify(saveUserPort, never()).save(any());
    }
}
