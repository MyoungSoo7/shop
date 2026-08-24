package github.lms.lemuel.shipping.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 배송비 산정 결과 — 총액 + 셀러별 내역.
 *
 * @param totalFee  주문 전체에 부과될 배송비
 * @param breakdown 셀러별 내역(sellerId 오름차순 — 같은 입력이면 같은 순서로 재현된다)
 */
public record ShippingFeeAssessment(BigDecimal totalFee, List<SellerShippingFee> breakdown) {

    public ShippingFeeAssessment {
        breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    }

    /** 부과할 배송비가 하나도 없는 결과. */
    public static ShippingFeeAssessment none() {
        return new ShippingFeeAssessment(BigDecimal.ZERO, List.of());
    }

    /**
     * 셀러 내역 조회 — 내역에 없으면 0 원 내역을 돌려준다.
     *
     * <p>{@code null} 을 돌려주면 호출부가 매번 null 검사를 해야 하고, 한 번 빠뜨리면 배송비가
     * NPE 로 터지거나 조용히 누락된다. "그 셀러엔 부과 없음"은 결과가 없는 게 아니라 0 원이다.
     */
    public SellerShippingFee forSeller(Long sellerId) {
        return breakdown.stream()
                .filter(fee -> fee.sellerId().equals(sellerId))
                .findFirst()
                .orElseGet(() -> SellerShippingFee.zero(sellerId));
    }
}
