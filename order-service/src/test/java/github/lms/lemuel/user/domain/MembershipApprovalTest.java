package github.lms.lemuel.user.domain;
import github.lms.lemuel.user.domain.exception.InvalidMembershipStateException;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipApprovalTest {

    @Test @DisplayName("생성 - 필수 필드 보존 및 createdAt 세팅")
    void create_valid() {
        MembershipApproval approval = new MembershipApproval(
                7L, MembershipAction.APPROVE, "사유", 99L);

        assertThat(approval.getUserId()).isEqualTo(7L);
        assertThat(approval.getAction()).isEqualTo(MembershipAction.APPROVE);
        assertThat(approval.getReason()).isEqualTo("사유");
        assertThat(approval.getProcessedBy()).isEqualTo(99L);
        assertThat(approval.getCreatedAt()).isNotNull();
        assertThat(approval.getId()).isNull();
    }

    @Test @DisplayName("생성 - userId 가 null 이면 예외")
    void create_nullUserId() {
        assertThatThrownBy(() -> new MembershipApproval(null, MembershipAction.APPROVE, "r", 1L))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("생성 - action 이 null 이면 예외")
    void create_nullAction() {
        assertThatThrownBy(() -> new MembershipApproval(1L, null, "r", 1L))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("생성 - processedBy 가 null 이면 예외")
    void create_nullProcessedBy() {
        assertThatThrownBy(() -> new MembershipApproval(1L, MembershipAction.REJECT, "r", null))
                .isInstanceOf(UserInvariantViolationException.class);
    }

    @Test @DisplayName("assignId - 영속화 후 식별자 주입")
    void assignId() {
        MembershipApproval approval = new MembershipApproval(1L, MembershipAction.SUSPEND, null, 2L);
        approval.assignId(55L);
        assertThat(approval.getId()).isEqualTo(55L);
    }
}
