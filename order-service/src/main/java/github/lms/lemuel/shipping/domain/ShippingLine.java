package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.math.BigDecimal;

/**
 * 배송비 산정 입력 1 줄 — 주문 라인에서 <b>배송비 계산에 필요한 사실만</b> 뽑아낸 값 객체.
 *
 * <p>주문 도메인({@code OrderItem})을 그대로 넘기지 않는 이유: 배송비 계산에 필요한 건
 * "누가 파는가(sellerId)·어떻게 부과하는가(chargeType)·얼마인가(individualFee/lineAmount)" 넷뿐이고,
 * 그 넷만 받으면 계산기가 주문·장바구니·견적(주문 전 배송비 미리보기) 어느 쪽에서도 재사용된다.
 *
 * @param sellerId      판매자 — 기본배송비는 이 단위로 묶여 1 회 부과된다
 * @param chargeType    부과 유형
 * @param individualFee {@link ShippingChargeType#INDIVIDUAL} 일 때 라인당 부과액(그 외 유형에서는 무시)
 * @param lineAmount    무료배송 임계 판정에 쓰는 라인 금액(= 단가 × 수량, 쿠폰 할인 전)
 */
public record ShippingLine(Long sellerId, ShippingChargeType chargeType,
                           BigDecimal individualFee, BigDecimal lineAmount) {

    public ShippingLine {
        if (sellerId == null) {
            throw new ShipmentInvariantViolationException("배송비 라인의 sellerId 는 필수입니다");
        }
        if (chargeType == null) {
            throw new ShipmentInvariantViolationException("배송비 라인의 chargeType 은 필수입니다");
        }
        if (lineAmount == null || lineAmount.signum() < 0) {
            throw new ShipmentInvariantViolationException("배송비 라인 금액은 0 이상이어야 합니다: " + lineAmount);
        }
        if (individualFee != null && individualFee.signum() < 0) {
            throw new ShipmentInvariantViolationException("개별배송비는 음수일 수 없습니다: " + individualFee);
        }
        // 개별배송 상품인데 금액이 비어 있으면 조용히 0 원 배송이 된다 — 부과 누락을 여기서 막는다.
        if (chargeType.chargesIndividual() && individualFee == null) {
            throw new ShipmentInvariantViolationException("INDIVIDUAL 라인은 개별배송비가 필수입니다");
        }
    }

    public static ShippingLine of(Long sellerId, ShippingChargeType chargeType,
                                  BigDecimal individualFee, BigDecimal lineAmount) {
        return new ShippingLine(sellerId, chargeType, individualFee, lineAmount);
    }

    /** 이 라인이 개별배송비로 기여하는 금액(개별배송 상품이 아니면 0). */
    public BigDecimal individualCharge() {
        return chargeType.chargesIndividual() ? individualFee : BigDecimal.ZERO;
    }
}
