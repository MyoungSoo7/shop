package github.lms.lemuel.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipStatusTest {

    @Test @DisplayName("fromString - 대소문자 무시 파싱")
    void fromString_valid() {
        assertThat(MembershipStatus.fromString("approved")).isEqualTo(MembershipStatus.APPROVED);
        assertThat(MembershipStatus.fromString("SUSPENDED")).isEqualTo(MembershipStatus.SUSPENDED);
    }

    @Test @DisplayName("fromString - 알 수 없거나 null 이면 PENDING 기본값")
    void fromString_fallback() {
        assertThat(MembershipStatus.fromString("nope")).isEqualTo(MembershipStatus.PENDING);
        assertThat(MembershipStatus.fromString(null)).isEqualTo(MembershipStatus.PENDING);
    }

    @Test @DisplayName("canUseService - APPROVED 만 true")
    void canUseService() {
        assertThat(MembershipStatus.APPROVED.canUseService()).isTrue();
        assertThat(MembershipStatus.PENDING.canUseService()).isFalse();
        assertThat(MembershipStatus.REJECTED.canUseService()).isFalse();
        assertThat(MembershipStatus.SUSPENDED.canUseService()).isFalse();
    }
}
