package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.domain.OrderStatus;

/**
 * 주문 상태머신 위반 — 허용되지 않은 전이({@code from → to})를 시도했다
 * (transitionTo 규칙, CREATED 만 취소/완료, PAID 만 환불).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이의 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidOrderStateException extends BusinessException {

    private final transient OrderStatus from;
    private final transient OrderStatus to;

    public InvalidOrderStateException(OrderStatus from, OrderStatus to) {
        super(ErrorCode.INVALID_STATE, "허용되지 않은 주문 상태 전이: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    /** 현재 상태에서 허용되지 않은 연산(전이 대상이 특정되지 않는 불변식) — Settlement 동형(from, operation). */
    public InvalidOrderStateException(OrderStatus from, String operation) {
        super(ErrorCode.INVALID_STATE, operation + " (현재 상태=" + from + ")");
        this.from = from;
        this.to = null;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
