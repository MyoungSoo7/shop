package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 결제 생성 시점에 주문(Order)이 결제 가능한 상태(CREATED)가 아닐 때 던진다.
 *
 * <p>payment 도메인의 예외지만 검증 대상은 <em>주문의 상태</em>다 — order 도메인의
 * {@code order.domain.exception.InvalidOrderStateException}(주문 자체 상태머신 위반)과 이름이
 * 겹쳐 혼동을 유발했기에, 실제 역할(결제 관점의 주문 상태 위반)을 드러내도록 명명했다.
 * {@code ErrorCode.INVALID_ORDER_STATE} 매핑·응답 계약은 동일하다.
 */
public class InvalidOrderStateForPaymentException extends BusinessException {
    public InvalidOrderStateForPaymentException(String message) {
        super(ErrorCode.INVALID_ORDER_STATE, message);
    }
}
