package github.lms.lemuel.coupon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CouponTypeTest {

    @Test @DisplayName("enum에 2개의 값이 존재한다")
    void values_count() {
        assertThat(CouponType.values()).hasSize(2);
        assertThat(CouponType.values()).containsExactly(CouponType.FIXED, CouponType.PERCENTAGE);
    }

    @Test @DisplayName("valueOf: 각 값 변환")
    void valueOf() {
        assertThat(CouponType.valueOf("FIXED")).isEqualTo(CouponType.FIXED);
        assertThat(CouponType.valueOf("PERCENTAGE")).isEqualTo(CouponType.PERCENTAGE);
    }

    @Test @DisplayName("valueOf: 잘못된 이름이면 예외")
    void valueOf_invalid() {
        assertThatThrownBy(() -> CouponType.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
