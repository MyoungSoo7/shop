package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import github.lms.lemuel.coupon.domain.DiscountTargetLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 쿠폰의 <b>적용 대상</b>이 결제 시점에 실제로 강제되는지에 대한 회귀 테스트.
 *
 * <p>고치기 전에는 {@code targetType} 이 목록 필터에서만 쓰이고 할인 계산은 소계 전체를 기준으로
 * 해서, 100번 상품 전용 10% 쿠폰이 10,000원짜리 그 상품이 담긴 100,000원 장바구니에서
 * <b>10,000원</b>을 깎았다(있어야 할 값은 1,000원). 아래 첫 테스트가 그 값을 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
class CouponTargetEnforcementTest {

    @Mock LoadCouponPort loadCouponPort;
    @Mock SaveCouponPort saveCouponPort;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock =
            Clock.fixed(LocalDateTime.of(2026, 3, 1, 12, 0).atZone(KST).toInstant(), KST);

    private CouponService service;

    @BeforeEach
    void setUp() {
        service = new CouponService(loadCouponPort, saveCouponPort, clock);
    }

    /** 장바구니: 100번 상품(카테고리 7) 10,000 + 999번 상품(카테고리 8) 90,000 = 소계 100,000. */
    private static List<DiscountTargetLine> cart() {
        return List.of(
                new DiscountTargetLine(100L, 7L, new BigDecimal("10000")),
                new DiscountTargetLine(999L, 8L, new BigDecimal("90000")));
    }

    private Coupon registered(String code, CouponType type, String value,
                              String targetType, Long targetId) {
        Coupon coupon = Coupon.create(code, type, new BigDecimal(value),
                BigDecimal.ZERO, null, 100, LocalDateTime.of(2026, 12, 31, 0, 0));
        coupon.configureTarget(targetType, targetId);
        coupon.assignId(1L);
        when(loadCouponPort.findByCode(code)).thenReturn(Optional.of(coupon));
        return coupon;
    }

    @Test
    @DisplayName("특정 상품 전용 쿠폰은 그 상품 라인만 깎는다 — 장바구니 전체를 깎으면 안 된다")
    void productCoupon_discountsOnlyMatchingLine() {
        registered("P10", CouponType.PERCENTAGE, "10", "PRODUCT", 100L);

        var result = service.validateCoupon("P10", 1L, cart());

        assertThat(result.valid()).isTrue();
        // 쿠폰이 걸리는 건 100번 상품 10,000 어치뿐이다 → 10% 는 1,000 이다(소계 기준이면 10,000).
        assertThat(result.discountAmount()).isEqualByComparingTo("1000");
        assertThat(result.eligibleAmount()).isEqualByComparingTo("10000");
        // 최종 금액은 소계 − 할인 = 99,000. 대상 밖 라인은 정가 그대로다.
        assertThat(result.finalAmount()).isEqualByComparingTo("99000");
    }

    @Test
    @DisplayName("카테고리 전용 쿠폰은 같은 카테고리 라인들만 합쳐 깎는다")
    void categoryCoupon_discountsMatchingCategoryOnly() {
        registered("C10", CouponType.PERCENTAGE, "10", "CATEGORY", 8L);

        var result = service.validateCoupon("C10", 1L, cart());

        assertThat(result.eligibleAmount()).isEqualByComparingTo("90000");
        assertThat(result.discountAmount()).isEqualByComparingTo("9000");
    }

    @Test
    @DisplayName("전체 적용 쿠폰은 예전 그대로 — 소계 전체가 기준")
    void allCoupon_unchanged() {
        registered("ALL10", CouponType.PERCENTAGE, "10", "ALL", null);

        var result = service.validateCoupon("ALL10", 1L, cart());

        assertThat(result.eligibleAmount()).isEqualByComparingTo("100000");
        assertThat(result.discountAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("대상 상품이 장바구니에 없으면 '0원 할인'이 아니라 적용 불가 — 안 그러면 쿠폰만 소모된다")
    void noEligibleLine_isInvalidNotZeroDiscount() {
        registered("P10", CouponType.PERCENTAGE, "10", "PRODUCT", 555L);

        var result = service.validateCoupon("P10", 1L, cart());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("사용할 수 있는 상품이");
        assertThat(result.discountAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("정액 쿠폰이 대상 금액보다 커도 대상 금액까지만 깎인다")
    void fixedCoupon_cappedAtEligibleBase() {
        registered("F50000", CouponType.FIXED, "50000", "PRODUCT", 100L);

        var result = service.validateCoupon("F50000", 1L, cart());

        // 대상은 10,000 뿐이므로 50,000 짜리 정액 쿠폰도 10,000 까지만 깎는다.
        assertThat(result.discountAmount()).isEqualByComparingTo("10000");
        assertThat(result.finalAmount()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("최소 주문 금액은 대상 금액이 아니라 소계 전체로 판정한다")
    void minOrderAmount_evaluatedAgainstSubtotal() {
        Coupon coupon = Coupon.create("MIN30000", CouponType.PERCENTAGE, new BigDecimal("10"),
                new BigDecimal("30000"), null, 100, LocalDateTime.of(2026, 12, 31, 0, 0));
        coupon.configureTarget("PRODUCT", 100L);
        coupon.assignId(1L);
        when(loadCouponPort.findByCode("MIN30000")).thenReturn(Optional.of(coupon));

        // 대상 라인은 10,000 뿐이지만 소계는 100,000 이라 "3만원 이상 구매 시 A상품 10%" 가 성립한다.
        var result = service.validateCoupon("MIN30000", 1L, cart());

        assertThat(result.valid()).isTrue();
        assertThat(result.discountAmount()).isEqualByComparingTo("1000");
    }
}
