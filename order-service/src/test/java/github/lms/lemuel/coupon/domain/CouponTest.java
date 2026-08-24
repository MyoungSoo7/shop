package github.lms.lemuel.coupon.domain;
import github.lms.lemuel.coupon.domain.exception.InvalidCouponStateException;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class CouponTest {

    @Test @DisplayName("정액 쿠폰 생성")
    void createFixed() {
        Coupon coupon = Coupon.create("FIXED5000", CouponType.FIXED, new BigDecimal("5000"),
                new BigDecimal("10000"), null, 100, LocalDateTime.now().plusDays(30));
        assertThat(coupon.getCode()).isEqualTo("FIXED5000");
        assertThat(coupon.getType()).isEqualTo(CouponType.FIXED);
    }

    @Test @DisplayName("정률 쿠폰 생성")
    void createPercentage() {
        Coupon coupon = Coupon.create("PERCENT10", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("5000"), 50, null);
        assertThat(coupon.getType()).isEqualTo(CouponType.PERCENTAGE);
    }

    @Test @DisplayName("코드 빈값이면 예외")
    void create_blankCode() {
        assertThatThrownBy(() -> Coupon.create("", CouponType.FIXED, new BigDecimal("1000"),
                null, null, 10, null))
                .isInstanceOf(CouponInvariantViolationException.class)
                .hasMessageContaining("코드");
    }

    @Test @DisplayName("할인값 0이면 예외")
    void create_zeroDiscount() {
        assertThatThrownBy(() -> Coupon.create("CODE", CouponType.FIXED, BigDecimal.ZERO,
                null, null, 10, null))
                .isInstanceOf(CouponInvariantViolationException.class);
    }

    @Test @DisplayName("정률 100% 초과 시 예외")
    void create_percentageOver100() {
        assertThatThrownBy(() -> Coupon.create("CODE", CouponType.PERCENTAGE, new BigDecimal("150"),
                null, null, 10, null))
                .isInstanceOf(CouponInvariantViolationException.class)
                .hasMessageContaining("100%");
    }

    @Test @DisplayName("최대 사용 횟수 0이면 예외")
    void create_zeroMaxUses() {
        assertThatThrownBy(() -> Coupon.create("CODE", CouponType.FIXED, new BigDecimal("1000"),
                null, null, 0, null))
                .isInstanceOf(CouponInvariantViolationException.class);
    }

    @Test @DisplayName("정액 할인 계산")
    void calculateDiscount_fixed() {
        Coupon coupon = Coupon.create("F", CouponType.FIXED, new BigDecimal("3000"),
                BigDecimal.ZERO, null, 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("50000"))).isEqualByComparingTo("3000");
    }

    @Test @DisplayName("정액 할인 — 주문 금액보다 크면 주문 금액까지만")
    void calculateDiscount_fixed_cappedByOrderAmount() {
        Coupon coupon = Coupon.create("F", CouponType.FIXED, new BigDecimal("10000"),
                BigDecimal.ZERO, null, 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("5000"))).isEqualByComparingTo("5000");
    }

    @Test @DisplayName("정률 할인 계산 (10%)")
    void calculateDiscount_percentage() {
        Coupon coupon = Coupon.create("P", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, null, 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("50000"))).isEqualByComparingTo("5000");
    }

    @Test @DisplayName("정률 할인 — 최대 할인 상한선 적용")
    void calculateDiscount_percentage_cappedByMax() {
        Coupon coupon = Coupon.create("P", CouponType.PERCENTAGE, new BigDecimal("50"),
                BigDecimal.ZERO, new BigDecimal("10000"), 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("100000"))).isEqualByComparingTo("10000");
    }

    @Test @DisplayName("비활성 쿠폰 검증 실패")
    void validate_inactive() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        coupon.deactivate();
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000"), T))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("비활성");
    }

    @Test @DisplayName("사용 한도 초과 시 검증 실패")
    void validate_exceededUsage() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 1, null);
        coupon.incrementUsage();
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000"), T))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("한도");
    }

    @Test @DisplayName("최소 주문 금액 미달 시 검증 실패")
    void validate_belowMinOrder() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                new BigDecimal("50000"), null, 10, null);
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("30000"), T))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("최소 주문");
    }

    @Test @DisplayName("만료된 쿠폰 검증 실패")
    void validate_expired() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, T.minusDays(1));
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000"), T))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("만료");
    }

    // ── 기간 판정은 시스템 시계가 아니라 주입된 시각을 따른다 ────────────────────────────
    // 컨테이너 JVM 이 UTC 일 때 LocalDateTime.now() 로 판정하면 KST 자정~09시 구간에서 만료
    // 경계가 하루 어긋난다(settlement TimeConfig 가 이미 겪은 off-by-one). 판정 시각을 밖에서
    // 주입해 경계를 결정적으로 고정한다.

    private static final LocalDateTime T = LocalDateTime.of(2026, 3, 1, 0, 0, 0);

    private static Coupon couponWithPeriod(LocalDateTime startsAt, LocalDateTime expiresAt) {
        Coupon coupon = Coupon.create("PERIOD", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, expiresAt);
        coupon.configurePeriod(startsAt, expiresAt);
        return coupon;
    }

    @Test @DisplayName("만료 경계: 만료 시각과 정확히 같은 순간은 아직 유효")
    void validate_atExactExpiry_stillValid() {
        Coupon coupon = couponWithPeriod(null, T);
        assertThatCode(() -> coupon.validate(new BigDecimal("50000"), T))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("만료 경계: 만료 시각 1나노 뒤는 만료")
    void validate_oneNanoAfterExpiry_throws() {
        Coupon coupon = couponWithPeriod(null, T);
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000"), T.plusNanos(1)))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("만료");
    }

    @Test @DisplayName("시작 경계: 시작 시각과 정확히 같은 순간은 사용 가능")
    void validate_atExactStart_valid() {
        Coupon coupon = couponWithPeriod(T, T.plusDays(30));
        assertThatCode(() -> coupon.validate(new BigDecimal("50000"), T))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("시작 경계: 시작 시각 1나노 전은 사용 불가")
    void validate_oneNanoBeforeStart_throws() {
        Coupon coupon = couponWithPeriod(T, T.plusDays(30));
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000"), T.minusNanos(1)))
                .isInstanceOf(InvalidCouponStateException.class)
                .hasMessageContaining("아직");
    }

    @Test @DisplayName("기간 판정은 시스템 시계에 의존하지 않는다 — 이미 지난 쿠폰도 그 이전 시각으로 판정하면 유효")
    void validate_doesNotDependOnSystemClock() {
        Coupon coupon = couponWithPeriod(
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2020, 1, 31, 0, 0));
        assertThatCode(() -> coupon.validate(new BigDecimal("50000"), LocalDateTime.of(2020, 1, 15, 0, 0)))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("사용 횟수 증가")
    void incrementUsage() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        coupon.incrementUsage();
        coupon.incrementUsage();
        assertThat(coupon.getUsedCount()).isEqualTo(2);
    }

    @Test @DisplayName("ALL 대상 쿠폰은 모든 상품/카테고리에 적용된다")
    void appliesTo_all() {
        Coupon coupon = Coupon.create("ALL", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        // targetType 미설정 → 기본 ALL
        assertThat(coupon.appliesTo(1L, 2L)).isTrue();
        assertThat(coupon.appliesTo(null, null)).isTrue();
    }

    @Test @DisplayName("PRODUCT 대상 쿠폰은 targetId 와 일치하는 상품에만 적용된다")
    void appliesTo_product() {
        Coupon coupon = Coupon.create("P", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        coupon.configureTarget("PRODUCT", 100L);
        assertThat(coupon.appliesTo(100L, 999L)).isTrue();
        assertThat(coupon.appliesTo(101L, 999L)).isFalse();
    }

    @Test @DisplayName("CATEGORY 대상 쿠폰은 targetId 와 일치하는 카테고리에만 적용된다")
    void appliesTo_category() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        coupon.configureTarget("CATEGORY", 50L);
        assertThat(coupon.appliesTo(1L, 50L)).isTrue();
        assertThat(coupon.appliesTo(1L, 51L)).isFalse();
    }

    @Test @DisplayName("지원하지 않는 적용 대상이면 예외")
    void configureTarget_unsupported() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        assertThatThrownBy(() -> coupon.configureTarget("SELLER", 1L))
                .isInstanceOf(CouponInvariantViolationException.class)
                .hasMessageContaining("지원하지 않는");
    }

    @Test @DisplayName("특정 대상(PRODUCT/CATEGORY) 쿠폰은 targetId 가 필수")
    void configureTarget_requiresTargetId() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        assertThatThrownBy(() -> coupon.configureTarget("PRODUCT", null))
                .isInstanceOf(CouponInvariantViolationException.class)
                .hasMessageContaining("targetId");
    }

    @Test @DisplayName("부분 환불 할인 계산")
    void calculateDiscountForRefund() {
        Coupon coupon = Coupon.create("F", CouponType.FIXED, new BigDecimal("10000"),
                BigDecimal.ZERO, null, 10, null);
        // 전체 주문 50000에서 10000 할인, 환불 대상 25000
        BigDecimal refundDiscount = coupon.calculateDiscountForRefund(
                new BigDecimal("50000"), new BigDecimal("25000"));
        // 10000 * 25000 / 50000 = 5000
        assertThat(refundDiscount).isEqualByComparingTo("5000");
    }
}
