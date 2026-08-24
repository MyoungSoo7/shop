package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.ShippingChargeType;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * 상품의 배송비 속성(판매자·부과 유형·개별배송비) 조회 포트.
 *
 * <p>product 도메인의 {@code Product} 를 그대로 끌어오지 않는다 — 배송비에 필요한 건 세 필드뿐이고,
 * 상품 애그리거트 전체를 로드하면 라인 수만큼 불필요한 조회가 붙는다. 어댑터는 productId 묶음을
 * 한 번에 읽는다(N+1 방지).
 */
public interface LoadProductShippingChargePort {

    /** productId → 배송비 속성. 존재하지 않는 상품은 결과 맵에서 빠진다. */
    Map<Long, ProductShippingCharge> loadByProductIds(Collection<Long> productIds);

    /**
     * @param productId     상품
     * @param sellerId      판매자 — 미할당이면 {@code null}(배송비 부과 대상에서 제외된다)
     * @param chargeType    배송비 부과 유형
     * @param individualFee {@link ShippingChargeType#INDIVIDUAL} 일 때 라인당 부과액
     */
    record ProductShippingCharge(Long productId, Long sellerId,
                                 ShippingChargeType chargeType, BigDecimal individualFee) {
    }
}
