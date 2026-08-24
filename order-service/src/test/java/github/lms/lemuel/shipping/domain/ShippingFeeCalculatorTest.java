package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배송비 산정 규칙 — 셀러별 기본배송비(조건부 무료) + 상품 개별배송비.
 *
 * <p>SSG B2E 실무 규칙 이식: 셀러 단위로 기본배송비를 1 회 부과하되 셀러별 주문 소계가
 * 무료배송 임계 이상이면 면제하고, 상품 개별배송비는 무료 조건과 무관하게 라인마다 부과한다.
 */
@DisplayName("ShippingFeeCalculator — 셀러별 조건부 무료배송 + 개별배송비")
class ShippingFeeCalculatorTest {

    private static final Long SELLER_A = 10L;
    private static final Long SELLER_B = 20L;

    private static SellerShippingPolicy policy(Long sellerId, String baseFee, String threshold) {
        return SellerShippingPolicy.of(sellerId, new BigDecimal(baseFee),
                threshold == null ? null : new BigDecimal(threshold));
    }

    private static ShippingLine base(Long sellerId, String lineAmount) {
        return ShippingLine.of(sellerId, ShippingChargeType.SELLER_BASE, null, new BigDecimal(lineAmount));
    }

    @Test
    @DisplayName("라인이 없으면 배송비는 0 이고 내역도 비어 있다")
    void emptyLines() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(List.of(), Map.of());

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
        assertThat(assessment.breakdown()).isEmpty();
    }

    @Test
    @DisplayName("셀러 소계가 무료배송 임계 미만이면 기본배송비를 부과한다")
    void chargesBaseFeeBelowThreshold() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "29000")),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000")));

        assertThat(assessment.totalFee()).isEqualByComparingTo("3000");
        assertThat(assessment.forSeller(SELLER_A).freeShippingApplied()).isFalse();
    }

    @Test
    @DisplayName("셀러 소계가 임계와 같으면 무료 — 경계는 이상(>=) 포함")
    void freeAtExactThreshold() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "30000")),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000")));

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
        assertThat(assessment.forSeller(SELLER_A).freeShippingApplied()).isTrue();
    }

    @Test
    @DisplayName("같은 셀러의 SELLER_BASE 라인이 여러 개여도 기본배송비는 1 회만 부과한다")
    void baseFeeChargedOncePerSeller() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "10000"), base(SELLER_A, "5000"), base(SELLER_A, "4000")),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000")));

        assertThat(assessment.totalFee()).isEqualByComparingTo("3000");
        assertThat(assessment.forSeller(SELLER_A).subtotal()).isEqualByComparingTo("19000");
    }

    @Test
    @DisplayName("셀러가 다르면 무료 판정도 따로 — 합배송으로 임계를 채울 수 없다")
    void perSellerThresholdIsIndependent() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "20000"), base(SELLER_B, "20000")),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000"),
                        SELLER_B, policy(SELLER_B, "2500", "30000")));

        assertThat(assessment.totalFee()).isEqualByComparingTo("5500");
        assertThat(assessment.breakdown()).hasSize(2);
    }

    @Test
    @DisplayName("개별배송비는 무료배송 조건과 무관하게 라인마다 부과한다")
    void individualFeeIgnoresFreeShipping() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "50000"),
                        ShippingLine.of(SELLER_A, ShippingChargeType.INDIVIDUAL,
                                new BigDecimal("5000"), new BigDecimal("10000")),
                        ShippingLine.of(SELLER_A, ShippingChargeType.INDIVIDUAL,
                                new BigDecimal("5000"), new BigDecimal("10000"))),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000")));

        // 소계 70,000 → 기본배송비 면제. 개별배송비 5,000 × 2 는 그대로 부과.
        assertThat(assessment.totalFee()).isEqualByComparingTo("10000");
        assertThat(assessment.forSeller(SELLER_A).baseFee()).isEqualByComparingTo("0");
        assertThat(assessment.forSeller(SELLER_A).individualFee()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("FREE 상품만 담기면 임계 미달이어도 배송비 0")
    void freeTypeNeverCharges() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(ShippingLine.of(SELLER_A, ShippingChargeType.FREE, null, new BigDecimal("1000"))),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000")));

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("배송비 정책이 없는 셀러는 부과하지 않는다 — 미등록 정책으로 고객에게 청구하지 않는다")
    void unknownSellerChargesNothing() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "1000")), Map.of());

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
        assertThat(assessment.forSeller(SELLER_A).freeShippingApplied()).isFalse();
    }

    @Test
    @DisplayName("무료배송 임계가 없는 정책은 금액과 무관하게 항상 기본배송비를 부과한다")
    void nullThresholdAlwaysCharges() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(base(SELLER_A, "1000000")),
                Map.of(SELLER_A, policy(SELLER_A, "3000", null)));

        assertThat(assessment.totalFee()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("SELLER_BASE 라인이 없으면 같은 셀러에 개별배송비만 남는다")
    void noBaseLineNoBaseFee() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(
                List.of(ShippingLine.of(SELLER_A, ShippingChargeType.INDIVIDUAL,
                        new BigDecimal("2500"), new BigDecimal("1000"))),
                Map.of(SELLER_A, policy(SELLER_A, "3000", "30000")));

        assertThat(assessment.totalFee()).isEqualByComparingTo("2500");
        assertThat(assessment.forSeller(SELLER_A).baseFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("내역에 없는 셀러를 조회하면 0 원 내역을 돌려준다 — null 대신")
    void forSellerMissingReturnsZero() {
        ShippingFeeAssessment assessment = ShippingFeeCalculator.assess(List.of(), Map.of());

        SellerShippingFee fee = assessment.forSeller(SELLER_B);
        assertThat(fee.total()).isEqualByComparingTo("0");
        assertThat(fee.sellerId()).isEqualTo(SELLER_B);
    }

    @Test
    @DisplayName("라인 불변식 — sellerId·금액 누락/음수는 거절")
    void lineInvariants() {
        assertThatThrownBy(() -> ShippingLine.of(null, ShippingChargeType.FREE, null, BigDecimal.TEN))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> ShippingLine.of(SELLER_A, null, null, BigDecimal.TEN))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> ShippingLine.of(SELLER_A, ShippingChargeType.FREE, null, new BigDecimal("-1")))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> ShippingLine.of(SELLER_A, ShippingChargeType.INDIVIDUAL,
                new BigDecimal("-1"), BigDecimal.TEN))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("INDIVIDUAL 라인은 개별배송비가 필수 — 누락 시 조용히 0 원 배송하지 않는다")
    void individualRequiresFee() {
        assertThatThrownBy(() -> ShippingLine.of(SELLER_A, ShippingChargeType.INDIVIDUAL, null, BigDecimal.TEN))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("정책 불변식 — 음수 기본배송비/임계는 거절")
    void policyInvariants() {
        assertThatThrownBy(() -> policy(SELLER_A, "-1", "30000"))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> policy(SELLER_A, "3000", "-1"))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> SellerShippingPolicy.of(null, BigDecimal.TEN, null))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("null 라인 목록·정책 맵은 빈 것으로 취급한다")
    void nullArgumentsAreEmpty() {
        assertThat(ShippingFeeCalculator.assess(null, null).totalFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rehydrate 는 저장된 정책을 그대로 복원한다")
    void rehydratePolicy() {
        SellerShippingPolicy restored = SellerShippingPolicy.rehydrate(
                SELLER_A, new BigDecimal("3000"), new BigDecimal("50000"));

        assertThat(restored.getSellerId()).isEqualTo(SELLER_A);
        assertThat(restored.getBaseFee()).isEqualByComparingTo("3000");
        assertThat(restored.getFreeThreshold()).isEqualByComparingTo("50000");
        assertThat(restored.baseFeeFor(new BigDecimal("50000"))).isEqualByComparingTo("0");
        assertThat(restored.baseFeeFor(new BigDecimal("49999"))).isEqualByComparingTo("3000");
    }
}
