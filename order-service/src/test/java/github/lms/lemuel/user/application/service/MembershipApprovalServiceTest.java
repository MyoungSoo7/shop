package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.domain.exception.InvalidMembershipStateException;

import github.lms.lemuel.user.application.port.out.LoadMembersByStatusPort;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.application.port.out.SaveMembershipApprovalPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.MembershipAction;
import github.lms.lemuel.user.domain.MembershipApproval;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MembershipApprovalServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;
    @Mock LoadMembersByStatusPort loadMembersByStatusPort;
    @Mock SaveMembershipApprovalPort saveMembershipApprovalPort;
    @Mock PublishUserEventPort publishUserEventPort;
    @InjectMocks MembershipApprovalService service;

    private User pendingCompany() {
        User user = User.createWithProfile("c@x.com", "hash", UserRole.COMPANY, "업체", "010-1111-2222");
        user.assignId(7L);
        user.markPending();
        return user;
    }

    @Test
    @DisplayName("승인: 상태가 APPROVED 로 저장되고 감사이력(APPROVE)이 기록된다")
    void approve_recordsAudit() {
        User user = pendingCompany();
        when(loadUserPort.findById(7L)).thenReturn(Optional.of(user));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.approve(7L, 99L);

        assertThat(result.getMembershipStatus()).isEqualTo(MembershipStatus.APPROVED);
        verify(saveUserPort).save(user);

        ArgumentCaptor<MembershipApproval> captor = ArgumentCaptor.forClass(MembershipApproval.class);
        verify(saveMembershipApprovalPort).save(captor.capture());
        MembershipApproval audit = captor.getValue();
        assertThat(audit.getAction()).isEqualTo(MembershipAction.APPROVE);
        assertThat(audit.getUserId()).isEqualTo(7L);
        assertThat(audit.getProcessedBy()).isEqualTo(99L);
    }

    @Test
    @DisplayName("반려: 사유와 함께 REJECT 이력이 기록된다")
    void reject_withReason() {
        User user = pendingCompany();
        when(loadUserPort.findById(7L)).thenReturn(Optional.of(user));
        when(saveUserPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.reject(7L, "서류 미비", 99L);

        assertThat(result.getMembershipStatus()).isEqualTo(MembershipStatus.REJECTED);
        ArgumentCaptor<MembershipApproval> captor = ArgumentCaptor.forClass(MembershipApproval.class);
        verify(saveMembershipApprovalPort).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(MembershipAction.REJECT);
        assertThat(captor.getValue().getReason()).isEqualTo("서류 미비");
    }

    @Test
    @DisplayName("잘못된 전이(APPROVED 회원 재승인)는 예외, 회원/이력 저장 안 함")
    void invalidTransition_doesNotSave() {
        User approved = pendingCompany();
        approved.approveMembership(); // 이미 APPROVED
        when(loadUserPort.findById(7L)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.approve(7L, 99L))
                .isInstanceOf(InvalidMembershipStateException.class);

        verify(saveUserPort, never()).save(any());
        verify(saveMembershipApprovalPort, never()).save(any());
    }

    @Test
    @DisplayName("회원이 없으면 UserNotFoundException")
    void notFound() {
        when(loadUserPort.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(404L, 99L))
                .isInstanceOf(UserNotFoundException.class);
        verify(saveMembershipApprovalPort, never()).save(any());
    }

    @Test
    @DisplayName("승인 대기 목록은 PENDING 상태로 조회한다")
    void getPendingMembers() {
        when(loadMembersByStatusPort.findByMembershipStatus(MembershipStatus.PENDING))
                .thenReturn(List.of(pendingCompany()));

        List<User> result = service.getPendingMembers();

        assertThat(result).hasSize(1);
        verify(loadMembersByStatusPort).findByMembershipStatus(MembershipStatus.PENDING);
    }
}
