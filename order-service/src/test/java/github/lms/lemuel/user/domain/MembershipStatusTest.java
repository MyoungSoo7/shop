package github.lms.lemuel.user.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipStatusTest {

    @Test @DisplayName("fromString - 대소문자 무시 파싱")
    void fromString_valid() {
        assertThat(MembershipStatus.fromString("approved")).isEqualTo(MembershipStatus.APPROVED);
        assertThat(MembershipStatus.fromString("SUSPENDED")).isEqualTo(MembershipStatus.SUSPENDED);
    }

    @Test @DisplayName("fromString - 모르는 값은 던진다 (PENDING 으로 떨어지면 승인된 회원이 대기로 둔갑)")
    void fromString_rejectsUnknown() {
        assertThatThrownBy(() -> MembershipStatus.fromString("nope"))
                .isInstanceOf(UnknownEnumValueException.class);
        assertThatThrownBy(() -> MembershipStatus.fromString(null))
                .isInstanceOf(UnknownEnumValueException.class);
    }

    @Test @DisplayName("fromStringOrNull - 모르는 값은 null")
    void fromStringOrNull_lenient() {
        assertThat(MembershipStatus.fromStringOrNull("approved")).isEqualTo(MembershipStatus.APPROVED);
        assertThat(MembershipStatus.fromStringOrNull("nope")).isNull();
        assertThat(MembershipStatus.fromStringOrNull(null)).isNull();
    }

    @Test @DisplayName("canUseService - APPROVED 만 true")
    void canUseService() {
        assertThat(MembershipStatus.APPROVED.canUseService()).isTrue();
        assertThat(MembershipStatus.PENDING.canUseService()).isFalse();
        assertThat(MembershipStatus.REJECTED.canUseService()).isFalse();
        assertThat(MembershipStatus.SUSPENDED.canUseService()).isFalse();
    }
}
