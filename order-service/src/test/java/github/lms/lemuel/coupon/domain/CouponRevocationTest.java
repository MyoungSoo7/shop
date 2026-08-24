package github.lms.lemuel.coupon.domain;

import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 쿠폰 사용 회수(주문 취소·환불 경로)의 도메인 규칙.
 *
 * <p>{@code incrementUsage} 의 역연산이지만 대칭이 아니다 — 사용 횟수를 음수로 내릴 수는 없다.
 * 중복 취소·경로 중복 호출이 실제로 일어나므로 하한을 도메인이 지킨다.
 */
class CouponRevocationTest {

    private Coupon coupon() {
        return Coupon.create("SAVE5000", CouponType.FIXED, new BigDecimal("5000"),
                BigDecimal.ZERO, null, 3, LocalDateTime.now().plusDays(30));
    }

    @Test
    @DisplayName("사용 회수는 사용 횟수를 1 되돌린다")
    void revokeUsage_decrementsUsedCount() {
        Coupon coupon = coupon();
        coupon.incrementUsage();
        coupon.incrementUsage();

        coupon.revokeUsage();

        assertThat(coupon.getUsedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("사용 이력이 없으면 회수를 거부한다 — 음수 사용 횟수는 한도 계산을 망가뜨린다")
    void revokeUsage_rejectsWhenNothingUsed() {
        Coupon coupon = coupon();

        assertThatThrownBy(coupon::revokeUsage)
                .isInstanceOf(CouponInvariantViolationException.class);
    }

    @Test
    @DisplayName("한도까지 쓴 쿠폰도 회수하면 다시 쓸 수 있다")
    void revokeUsage_reopensExhaustedCoupon() {
        Coupon coupon = Coupon.create("ONESHOT", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 1, LocalDateTime.now().plusDays(1));
        coupon.incrementUsage();

        assertThatThrownBy(() -> coupon.validate(new BigDecimal("10000"), LocalDateTime.now()))
                .hasMessageContaining("사용");

        coupon.revokeUsage();

        coupon.validate(new BigDecimal("10000"), LocalDateTime.now()); // 예외 없음
        assertThat(coupon.getUsedCount()).isZero();
    }
}
