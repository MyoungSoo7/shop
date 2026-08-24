package github.lms.lemuel.user.domain;
import github.lms.lemuel.user.domain.exception.InvalidMembershipStateException;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMembershipTest {

    private static User company() {
        return User.createWithProfile("company@x.com", "hash", UserRole.COMPANY, "업체", "010-1111-2222");
    }

    @Test
    @DisplayName("일반 회원은 기본 APPROVED, 승인 불필요")
    void normalUser_approvedByDefault() {
        User user = User.create("user@x.com", "hash");

        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.APPROVED);
        assertThat(user.requiresApproval()).isFalse();
        assertThat(user.canUseService()).isTrue();
    }

    @Test
    @DisplayName("업체 회원/시공기사는 승인이 필요하다")
    void companyAndTechnician_requireApproval() {
        assertThat(company().requiresApproval()).isTrue();
        User tech = User.createWithProfile("t@x.com", "hash", UserRole.TECHNICIAN, "기사", "010-3333-4444");
        assertThat(tech.requiresApproval()).isTrue();
    }

    @Test
    @DisplayName("markPending → PENDING 이면 서비스 이용 불가")
    void markPending() {
        User user = company();
        user.markPending();

        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(user.canUseService()).isFalse();
    }

    @Test
    @DisplayName("승인: PENDING → APPROVED")
    void approve() {
        User user = company();
        user.markPending();

        user.approveMembership();

        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.APPROVED);
        assertThat(user.canUseService()).isTrue();
    }

    @Test
    @DisplayName("반려: PENDING → REJECTED")
    void reject() {
        User user = company();
        user.markPending();

        user.rejectMembership();

        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(user.canUseService()).isFalse();
    }

    @Test
    @DisplayName("정지/정지해제: APPROVED → SUSPENDED → APPROVED")
    void suspendAndReinstate() {
        User user = company();
        user.markPending();
        user.approveMembership();

        user.suspendMembership();
        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.SUSPENDED);
        assertThat(user.canUseService()).isFalse();

        user.reinstateMembership();
        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.APPROVED);
    }

    @Test
    @DisplayName("APPROVED 회원을 승인하려 하면 예외 (PENDING 아님)")
    void approve_fromApprovedFails() {
        User user = company(); // APPROVED 기본값

        assertThatThrownBy(user::approveMembership)
                .isInstanceOfSatisfying(InvalidMembershipStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(MembershipStatus.APPROVED);
                    assertThat(ex.getTo()).isEqualTo(MembershipStatus.PENDING);
                })
                .hasMessageContaining("expected PENDING");
    }

    @Test
    @DisplayName("PENDING 회원은 정지할 수 없다 (APPROVED 아님)")
    void suspend_fromPendingFails() {
        User user = company();
        user.markPending();

        assertThatThrownBy(user::suspendMembership)
                .isInstanceOf(InvalidMembershipStateException.class)
                .hasMessageContaining("expected APPROVED");
    }

    @Test
    @DisplayName("정지 상태가 아니면 정지 해제 불가")
    void reinstate_fromApprovedFails() {
        User user = company();

        assertThatThrownBy(user::reinstateMembership)
                .isInstanceOf(InvalidMembershipStateException.class)
                .hasMessageContaining("expected SUSPENDED");
    }
}
