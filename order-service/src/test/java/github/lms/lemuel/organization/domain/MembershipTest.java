package github.lms.lemuel.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipTest {

    @Test
    @DisplayName("조직 생성자 OWNER 는 즉시 ACTIVE, self-invited")
    void owner_isActiveOwner() {
        Membership m = Membership.owner(1L, 100L);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(m.getRole()).isEqualTo(OrgRole.OWNER);
        assertThat(m.getInvitedBy()).isEqualTo(100L);
        assertThat(m.isActiveOwner()).isTrue();
        assertThat(m.occupiesActiveSlot()).isTrue();
    }

    @Test
    @DisplayName("초대는 INVITED, 수락 시 ACTIVE 로 전이")
    void invite_thenAccept() {
        Membership m = Membership.invite(1L, 200L, OrgRole.STAFF, 100L);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.INVITED);
        assertThat(m.occupiesActiveSlot()).isTrue();   // 초대 대기도 슬롯 점유

        m.accept();
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("제거된 멤버십은 슬롯을 비우고 재전이 불가(터미널)")
    void remove_isTerminalAndFreesSlot() {
        Membership m = Membership.invite(1L, 200L, OrgRole.STAFF, 100L);
        m.remove();

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.REMOVED);
        assertThat(m.occupiesActiveSlot()).isFalse();
        assertThatThrownBy(m::accept).isInstanceOf(InvalidMembershipTransitionException.class);
    }

    @Test
    @DisplayName("ACTIVE ⇄ SUSPENDED 전이, INVITED 에서 바로 suspend 는 위반")
    void suspendReactivate_andInvalidFromInvited() {
        Membership m = Membership.invite(1L, 200L, OrgRole.MANAGER, 100L);
        m.accept();

        m.suspend();
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.SUSPENDED);
        m.reactivate();
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);

        Membership invited = Membership.invite(1L, 300L, OrgRole.STAFF, 100L);
        assertThatThrownBy(invited::suspend).isInstanceOf(InvalidMembershipTransitionException.class);
    }

    @Test
    @DisplayName("역할 변경은 가능하나 REMOVED 멤버십은 변경 불가")
    void changeRole_blockedWhenRemoved() {
        Membership m = Membership.invite(1L, 200L, OrgRole.STAFF, 100L);
        m.accept();
        m.changeRole(OrgRole.MANAGER);
        assertThat(m.getRole()).isEqualTo(OrgRole.MANAGER);

        m.remove();
        assertThatThrownBy(() -> m.changeRole(OrgRole.STAFF))
                .isInstanceOf(InvalidMembershipTransitionException.class);
    }

    @Test
    @DisplayName("isActiveOwner 는 활성+OWNER 일 때만 참")
    void isActiveOwner_predicate() {
        Membership invitedOwner = Membership.invite(1L, 200L, OrgRole.OWNER, 100L);
        assertThat(invitedOwner.isActiveOwner()).isFalse();   // 아직 INVITED

        Membership staff = Membership.owner(1L, 100L);
        staff.changeRole(OrgRole.STAFF);
        assertThat(staff.isActiveOwner()).isFalse();          // OWNER 아님
    }
}
