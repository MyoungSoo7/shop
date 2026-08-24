package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 주문 도메인 불변식 위반 — 아이템/금액/할인/수량 규칙을 어겼다
 * (다건 주문 아이템 최소 1개, 할인 음수·소계 초과, userId·amount·수량 규칙, RefundPolicy 입력).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 상태 전이 위반은 {@link InvalidOrderStateException} 가 담당한다.
 */
public class OrderInvariantViolationException extends BusinessException {

    public OrderInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
