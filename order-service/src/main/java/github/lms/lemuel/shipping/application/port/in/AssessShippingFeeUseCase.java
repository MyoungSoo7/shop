package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.ShippingFeeAssessment;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주문 라인으로 배송비를 산정한다 — 주문 생성·부분 취소 재평가·결제 전 미리보기가 공유하는 단일 입구.
 *
 * <p>호출부는 "무엇을 얼마어치 사는가"(productId·금액)만 넘긴다. 누가 파는지(sellerId)·어떤 유형으로
 * 부과하는지는 상품 마스터가 진실의 원천이므로 이 유스케이스가 직접 조회한다 — 요청 파라미터로
 * 받으면 배송비를 클라이언트가 정하게 된다.
 */
public interface AssessShippingFeeUseCase {

    ShippingFeeAssessment assess(List<OrderLine> lines);

    /**
     * 배송비 산정 대상 주문 라인.
     *
     * @param productId  상품 — 셀러·배송비 유형 해석의 키
     * @param lineAmount 무료배송 임계 판정에 쓰는 라인 금액(단가 × 수량, 쿠폰 할인 전)
     */
    record OrderLine(Long productId, BigDecimal lineAmount) {
        public OrderLine {
            if (productId == null) {
                throw new github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException(
                        "배송비 산정 라인의 productId 는 필수입니다");
            }
            if (lineAmount == null || lineAmount.signum() < 0) {
                throw new github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException(
                        "배송비 산정 라인 금액은 0 이상이어야 합니다: " + lineAmount);
            }
        }
    }
}
