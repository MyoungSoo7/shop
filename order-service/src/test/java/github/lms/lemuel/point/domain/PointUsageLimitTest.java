package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointUsageLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 사용 한도.
 *
 * <p>지금까지 포인트는 잔액만 있으면 결제 전액을 덮을 수 있었다. 실무 커머스는 대개 상한을 둔다 —
 * 정액("주문당 최대 1 만 포인트")이거나 주문금액 비율("결제액의 30% 까지")이다. 상한을 코드가 아니라
 * 정책 데이터로 두어야 판촉 기간마다 배포하지 않는다.
 */
@DisplayName("PointUsageLimit — 주문당 포인트 사용 상한")
class PointUsageLimitTest {

    @Test
    @DisplayName("한도 없음이면 주문금액 전액까지 쓸 수 있다")
    void noneAllowsFullAmount() {
        PointUsageLimit limit = PointUsageLimit.none();

        assertThat(limit.maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("50000");
        assertThatCode(() -> limit.assertWithin(new BigDecimal("50000"), new BigDecimal("50000")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정액 한도 — 주문금액보다 한도가 작으면 한도가 상한")
    void fixedAmountCaps() {
        PointUsageLimit limit = PointUsageLimit.fixedAmount(new BigDecimal("10000"));

        assertThat(limit.maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("정액 한도 — 주문금액이 한도보다 작으면 주문금액이 상한(더 낼 수는 없다)")
    void fixedAmountNeverExceedsOrder() {
        PointUsageLimit limit = PointUsageLimit.fixedAmount(new BigDecimal("10000"));

        assertThat(limit.maxUsable(new BigDecimal("3000"))).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("비율 한도 — 결제액의 30%, 원 미만은 버린다(상한이 늘어나면 안 된다)")
    void ratioCaps() {
        PointUsageLimit limit = PointUsageLimit.orderRatio(new BigDecimal("30"));

        assertThat(limit.maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("15000");
        assertThat(limit.maxUsable(new BigDecimal("3333"))).isEqualByComparingTo("999"); // 999.9 → 999
    }

    @Test
    @DisplayName("비율 100% 는 전액 허용과 같다")
    void ratioHundred() {
        assertThat(PointUsageLimit.orderRatio(new BigDecimal("100")).maxUsable(new BigDecimal("777")))
                .isEqualByComparingTo("777");
    }

    @Test
    @DisplayName("상한을 넘는 사용 요청은 거절 — 경계(정확히 상한)는 허용")
    void assertWithinBoundary() {
        PointUsageLimit limit = PointUsageLimit.orderRatio(new BigDecimal("30"));

        assertThatCode(() -> limit.assertWithin(new BigDecimal("50000"), new BigDecimal("15000")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> limit.assertWithin(new BigDecimal("50000"), new BigDecimal("15001")))
                .isInstanceOf(PointUsageLimitExceededException.class);
    }

    @Test
    @DisplayName("사용액이 없거나 0 이면 한도와 무관하게 통과")
    void zeroUsageAlwaysPasses() {
        PointUsageLimit limit = PointUsageLimit.fixedAmount(BigDecimal.ZERO);

        assertThatCode(() -> limit.assertWithin(new BigDecimal("50000"), BigDecimal.ZERO))
                .doesNotThrowAnyException();
        assertThatCode(() -> limit.assertWithin(new BigDecimal("50000"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도 0 은 포인트 사용 금지를 뜻한다 — 1 원도 거절")
    void zeroLimitBlocksUsage() {
        PointUsageLimit limit = PointUsageLimit.fixedAmount(BigDecimal.ZERO);

        assertThat(limit.maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> limit.assertWithin(new BigDecimal("50000"), BigDecimal.ONE))
                .isInstanceOf(PointUsageLimitExceededException.class);
    }

    @Test
    @DisplayName("불변식 — 음수 한도·범위를 벗어난 비율은 거절")
    void invariants() {
        assertThatThrownBy(() -> PointUsageLimit.fixedAmount(new BigDecimal("-1")))
                .isInstanceOf(InvalidPointStateException.class);
        assertThatThrownBy(() -> PointUsageLimit.fixedAmount(null))
                .isInstanceOf(InvalidPointStateException.class);
        assertThatThrownBy(() -> PointUsageLimit.orderRatio(new BigDecimal("-1")))
                .isInstanceOf(InvalidPointStateException.class);
        assertThatThrownBy(() -> PointUsageLimit.orderRatio(new BigDecimal("101")))
                .isInstanceOf(InvalidPointStateException.class);
    }

    @Test
    @DisplayName("주문금액이 0 이하면 쓸 수 있는 포인트도 0")
    void nonPositiveOrderAmount() {
        assertThat(PointUsageLimit.none().maxUsable(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(PointUsageLimit.none().maxUsable(null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("복원 팩토리는 저장된 유형·값을 그대로 되살린다")
    void rehydrate() {
        PointUsageLimit restored = PointUsageLimit.rehydrate(
                PointUsageLimitType.ORDER_RATIO, null, new BigDecimal("30"));

        assertThat(restored.getType()).isEqualTo(PointUsageLimitType.ORDER_RATIO);
        assertThat(restored.maxUsable(new BigDecimal("50000"))).isEqualByComparingTo("15000");
    }
}
