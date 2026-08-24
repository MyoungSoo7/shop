package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.exception.DuplicateMembershipException;
import github.lms.lemuel.organization.application.exception.ForbiddenOrgAccessException;
import github.lms.lemuel.organization.application.exception.MembershipNotFoundException;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase.ChangeRoleCommand;
import github.lms.lemuel.organization.application.port.in.MembershipCommandUseCase.InviteCommand;
import github.lms.lemuel.organization.application.port.out.LoadMembershipPort;
import github.lms.lemuel.organization.application.port.out.PublishOrganizationEventPort;
import github.lms.lemuel.organization.application.port.out.SaveMembershipPort;
import github.lms.lemuel.organization.domain.InvalidMembershipTransitionException;
import github.lms.lemuel.organization.domain.LastOwnerException;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipCommandServiceTest {

    private OrgAuthorizer authorizer;
    private LoadMembershipPort loadMembership;
    private SaveMembershipPort saveMembership;
    private PublishOrganizationEventPort publish;
    private MembershipCommandService service;

    @BeforeEach
    void setUp() {
        authorizer = mock(OrgAuthorizer.class);
        loadMembership = mock(LoadMembershipPort.class);
        saveMembership = mock(SaveMembershipPort.class);
        publish = mock(PublishOrganizationEventPort.class);
        service = new MembershipCommandService(authorizer, loadMembership, saveMembership, publish);
        when(saveMembership.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Membership activeMember(Long userId, OrgRole role) {
        return Membership.builder().id(9L).organizationId(1L).userId(userId)
                .role(role).status(MembershipStatus.ACTIVE).invitedBy(1L).build();
    }

    @Test
    @DisplayName("초대 성공 → INVITED 멤버십 생성")
    void invite_success() {
        when(loadMembership.findSlotOccupant(1L, 200L)).thenReturn(Optional.empty());

        Membership m = service.invite(new InviteCommand(1L, 200L, OrgRole.STAFF, 100L));

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.INVITED);
        assertThat(m.getRole()).isEqualTo(OrgRole.STAFF);
        verify(saveMembership).save(any());
    }

    @Test
    @DisplayName("이미 활성 슬롯 점유 시 초대는 409(중복)")
    void invite_duplicate() {
        when(loadMembership.findSlotOccupant(1L, 200L))
                .thenReturn(Optional.of(activeMember(200L, OrgRole.STAFF)));

        assertThatThrownBy(() -> service.invite(new InviteCommand(1L, 200L, OrgRole.STAFF, 100L)))
                .isInstanceOf(DuplicateMembershipException.class);
        verify(saveMembership, never()).save(any());
    }

    @Test
    @DisplayName("권한 없는 초대는 403")
    void invite_forbidden() {
        when(authorizer.requireRole(eq(1L), eq(100L), any(), anyString()))
                .thenThrow(new ForbiddenOrgAccessException("권한 없음"));

        assertThatThrownBy(() -> service.invite(new InviteCommand(1L, 200L, OrgRole.STAFF, 100L)))
                .isInstanceOf(ForbiddenOrgAccessException.class);
    }

    @Test
    @DisplayName("초대 수락 → ACTIVE 전이 + member_joined 발행")
    void accept_success() {
        Membership invited = Membership.invite(1L, 200L, OrgRole.STAFF, 100L);
        when(loadMembership.findSlotOccupant(1L, 200L)).thenReturn(Optional.of(invited));

        Membership m = service.accept(1L, 200L);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        verify(publish).publishMemberJoined(any());
    }

    @Test
    @DisplayName("초대 없이 수락하면 404")
    void accept_notFound() {
        when(loadMembership.findSlotOccupant(1L, 200L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(1L, 200L))
                .isInstanceOf(MembershipNotFoundException.class);
    }

    @Test
    @DisplayName("이미 ACTIVE 인데 수락하면 상태머신 위반(409)")
    void accept_alreadyActive() {
        when(loadMembership.findSlotOccupant(1L, 200L))
                .thenReturn(Optional.of(activeMember(200L, OrgRole.STAFF)));

        assertThatThrownBy(() -> service.accept(1L, 200L))
                .isInstanceOf(InvalidMembershipTransitionException.class);
    }

    @Test
    @DisplayName("마지막 OWNER 강등은 422(LastOwner)")
    void changeRole_lastOwnerBlocked() {
        when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(new ChangeRoleCommand(1L, 100L, OrgRole.STAFF, 100L)))
                .isInstanceOf(LastOwnerException.class);
    }

    @Test
    @DisplayName("OWNER 가 2명이면 강등 가능")
    void changeRole_success_whenAnotherOwner() {
        when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(2L);

        Membership m = service.changeRole(new ChangeRoleCommand(1L, 100L, OrgRole.MANAGER, 100L));

        assertThat(m.getRole()).isEqualTo(OrgRole.MANAGER);
    }

    @Test
    @DisplayName("역할 변경 성공 시 member_role_changed 를 이전 역할과 함께 발행한다")
    void changeRole_publishesEventWithPreviousRole() {
        when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(2L);

        service.changeRole(new ChangeRoleCommand(1L, 100L, OrgRole.MANAGER, 100L));

        verify(publish).publishMemberRoleChanged(any(), eq(OrgRole.OWNER));
    }

    @Test
    @DisplayName("마지막 OWNER 제거는 422")
    void remove_lastOwnerBlocked() {
        when(loadMembership.findSlotOccupant(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(1L, 100L, 100L))
                .isInstanceOf(LastOwnerException.class);
        verify(saveMembership, never()).save(any());
    }

    @Test
    @DisplayName("일반 멤버 제거 성공 → REMOVED")
    void remove_success() {
        Membership staff = activeMember(200L, OrgRole.STAFF);
        when(loadMembership.findSlotOccupant(1L, 200L)).thenReturn(Optional.of(staff));

        service.remove(1L, 200L, 100L);

        assertThat(staff.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        verify(saveMembership).save(staff);
    }

    @Test
    @DisplayName("멤버 제거 성공 시 member_removed 를 발행한다 — 소비측이 카드를 정지시킨다")
    void remove_publishesEvent() {
        Membership staff = activeMember(200L, OrgRole.STAFF);
        when(loadMembership.findSlotOccupant(1L, 200L)).thenReturn(Optional.of(staff));

        service.remove(1L, 200L, 100L);

        verify(publish).publishMemberRemoved(staff);
    }

    @Test
    @DisplayName("마지막 OWNER 제거가 차단되면 이벤트를 발행하지 않는다")
    void remove_blocked_publishesNothing() {
        when(loadMembership.findSlotOccupant(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(1L, 100L, 100L))
                .isInstanceOf(LastOwnerException.class);
        verify(publish, never()).publishMemberRemoved(any());
    }

    @Test
    @DisplayName("권한 없는 제거는 403")
    void remove_forbidden() {
        doThrow(new ForbiddenOrgAccessException("권한 없음"))
                .when(authorizer).requireRole(eq(1L), eq(300L), any(), anyString());

        assertThatThrownBy(() -> service.remove(1L, 200L, 300L))
                .isInstanceOf(ForbiddenOrgAccessException.class);
    }
}
