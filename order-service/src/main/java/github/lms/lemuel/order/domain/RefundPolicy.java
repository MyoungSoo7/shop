package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.math.BigDecimal;

/**
 * 환불 금액 정책 — 배송 상태에 따라 최종 환불(PG 취소) 금액을 계산하는 순수 도메인 로직.
 *
 * <p>규칙:
 * <ul>
 *   <li><b>배송 시작 전</b>(shippingStarted=false): 전액 환불. 배송비를 포함해 결제한 전부를 돌려준다.</li>
 *   <li><b>배송 시작 후</b>(shippingStarted=true): 배송비를 차감한 금액만 환불. 이미 발생한 배송비는
 *       고객이 부담한다. 단 차감액이 결제 금액을 넘지 않도록 clamp 한다.</li>
 * </ul>
 *
 * <p>{@code paidAmount} 는 "고객이 실제로 결제한 금액"이며, 배송비를 별도 청구했다면 그 배송비도
 * 포함된 값이어야 배송비 차감이 재무적으로 성립한다(배송비가 0 이면 항상 전액 환불).
 *
 * <p>프레임워크 의존이 전혀 없는 정적 계산이라 단위 테스트로 모든 분기를 검증할 수 있다.
 */
public final class RefundPolicy {

    private RefundPolicy() {}

    public static RefundOutcome forOrder(BigDecimal paidAmount, BigDecimal shippingFee, boolean shippingStarted) {
        if (paidAmount == null || paidAmount.signum() < 0) {
            throw new OrderInvariantViolationException("결제 금액은 0 이상이어야 합니다: " + paidAmount);
        }
        BigDecimal fee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        if (fee.signum() < 0) {
            throw new OrderInvariantViolationException("배송비는 음수일 수 없습니다: " + fee);
        }

        if (!shippingStarted) {
            return new RefundOutcome(paidAmount, BigDecimal.ZERO);
        }
        BigDecimal deduction = fee.min(paidAmount); // 결제액 초과 차감 방지
        return new RefundOutcome(paidAmount.subtract(deduction), deduction);
    }

    /**
     * 환불 계산 결과.
     *
     * @param refundableAmount    실제 환불(PG 취소)할 금액
     * @param deductedShippingFee 배송 시작으로 차감된 배송비(전액 환불이면 0)
     */
    public record RefundOutcome(BigDecimal refundableAmount, BigDecimal deductedShippingFee) {
        /** 배송비 차감이 발생했는지(=부분 환불인지). */
        public boolean deductsShippingFee() {
            return deductedShippingFee.signum() > 0;
        }
    }
}
