package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase.OrderLine;
import github.lms.lemuel.shipping.application.port.out.LoadProductShippingChargePort;
import github.lms.lemuel.shipping.application.port.out.LoadProductShippingChargePort.ProductShippingCharge;
import github.lms.lemuel.shipping.application.port.out.LoadSellerShippingPolicyPort;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import github.lms.lemuel.shipping.domain.ShippingChargeType;
import github.lms.lemuel.shipping.domain.ShippingFeeAssessment;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AssessShippingFeeService — 주문 라인 → 배송비 산정")
class AssessShippingFeeServiceTest {

    private static final Long SELLER = 10L;

    private LoadProductShippingChargePort chargePort;
    private LoadSellerShippingPolicyPort policyPort;
    private AssessShippingFeeService service;

    @BeforeEach
    void setUp() {
        chargePort = mock(LoadProductShippingChargePort.class);
        policyPort = mock(LoadSellerShippingPolicyPort.class);
        service = new AssessShippingFeeService(chargePort, policyPort);
    }

    private static ProductShippingCharge charge(Long productId, Long sellerId,
                                                ShippingChargeType type, String fee) {
        return new ProductShippingCharge(productId, sellerId, type,
                fee == null ? null : new BigDecimal(fee));
    }

    @Test
    @DisplayName("라인이 없으면 조회 없이 0 원 — 빈 주문으로 DB 를 때리지 않는다")
    void emptyLinesShortCircuits() {
        ShippingFeeAssessment assessment = service.assess(List.of());

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
        verifyNoInteractions(chargePort, policyPort);
    }

    @Test
    @DisplayName("같은 셀러 두 라인의 합이 임계 미달이면 기본배송비 1 회 부과")
    void chargesSellerBaseOnce() {
        when(chargePort.loadByProductIds(anyCollection())).thenReturn(Map.of(
                1L, charge(1L, SELLER, ShippingChargeType.SELLER_BASE, null),
                2L, charge(2L, SELLER, ShippingChargeType.SELLER_BASE, null)));
        when(policyPort.loadBySellerIds(anyCollection())).thenReturn(Map.of(
                SELLER, SellerShippingPolicy.of(SELLER, new BigDecimal("3000"), new BigDecimal("50000"))));

        ShippingFeeAssessment assessment = service.assess(List.of(
                new OrderLine(1L, new BigDecimal("20000")),
                new OrderLine(2L, new BigDecimal("15000"))));

        assertThat(assessment.totalFee()).isEqualByComparingTo("3000");
        assertThat(assessment.forSeller(SELLER).subtotal()).isEqualByComparingTo("35000");
    }

    @Test
    @DisplayName("합이 임계 이상이면 무료 — 라인이 쪼개져 있어도 셀러 단위로 합산한다")
    void freeWhenSellerSubtotalReachesThreshold() {
        when(chargePort.loadByProductIds(anyCollection())).thenReturn(Map.of(
                1L, charge(1L, SELLER, ShippingChargeType.SELLER_BASE, null),
                2L, charge(2L, SELLER, ShippingChargeType.SELLER_BASE, null)));
        when(policyPort.loadBySellerIds(anyCollection())).thenReturn(Map.of(
                SELLER, SellerShippingPolicy.of(SELLER, new BigDecimal("3000"), new BigDecimal("30000"))));

        ShippingFeeAssessment assessment = service.assess(List.of(
                new OrderLine(1L, new BigDecimal("20000")),
                new OrderLine(2L, new BigDecimal("10000"))));

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
        assertThat(assessment.forSeller(SELLER).freeShippingApplied()).isTrue();
    }

    @Test
    @DisplayName("개별배송 상품은 무료 조건을 넘겨도 라인마다 부과된다")
    void individualChargeSurvivesFreeShipping() {
        when(chargePort.loadByProductIds(anyCollection())).thenReturn(Map.of(
                1L, charge(1L, SELLER, ShippingChargeType.SELLER_BASE, null),
                2L, charge(2L, SELLER, ShippingChargeType.INDIVIDUAL, "5000")));
        when(policyPort.loadBySellerIds(anyCollection())).thenReturn(Map.of(
                SELLER, SellerShippingPolicy.of(SELLER, new BigDecimal("3000"), new BigDecimal("30000"))));

        ShippingFeeAssessment assessment = service.assess(List.of(
                new OrderLine(1L, new BigDecimal("40000")),
                new OrderLine(2L, new BigDecimal("10000"))));

        assertThat(assessment.totalFee()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("배송비 속성을 못 찾은 상품은 부과 대상에서 빠진다 — 모르는 상품에 청구하지 않는다")
    void unknownProductIsSkipped() {
        when(chargePort.loadByProductIds(anyCollection())).thenReturn(Map.of());

        ShippingFeeAssessment assessment = service.assess(List.of(new OrderLine(99L, new BigDecimal("1000"))));

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
        assertThat(assessment.breakdown()).isEmpty();
    }

    @Test
    @DisplayName("셀러 미할당 상품(seller_id NULL)도 부과 대상에서 빠진다")
    void productWithoutSellerIsSkipped() {
        when(chargePort.loadByProductIds(anyCollection())).thenReturn(Map.of(
                1L, charge(1L, null, ShippingChargeType.SELLER_BASE, null)));

        ShippingFeeAssessment assessment = service.assess(List.of(new OrderLine(1L, new BigDecimal("1000"))));

        assertThat(assessment.totalFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("null 라인 목록도 0 원으로 다룬다")
    void nullLinesAreEmpty() {
        assertThat(service.assess(null).totalFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("주문 라인 불변식 — productId 누락·음수 금액 거절")
    void orderLineInvariants() {
        assertThatThrownBy(() -> new OrderLine(null, BigDecimal.TEN))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, new BigDecimal("-1")))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, null))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }
}
