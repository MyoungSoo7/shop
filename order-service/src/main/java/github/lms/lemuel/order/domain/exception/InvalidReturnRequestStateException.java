package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.domain.ReturnRequestStatus;

/**
 * 반품·교환 신청의 상태머신 위반.
 *
 * <p>{@link InvalidOrderStateException} 을 쓰지 않는 이유는 그 예외가 <b>주문</b> 상태를 구조적으로
 * 보존하기 때문이다({@code getFrom()}/{@code getTo()} 가 {@code OrderStatus}). 신청 상태를 거기에
 * 억지로 담으면 두 상태 축이 한 필드에서 섞여, 로그만 보고는 어느 축의 위반인지 알 수 없다.
 */
public class InvalidReturnRequestStateException extends BusinessException {

    private final transient ReturnRequestStatus from;

    public InvalidReturnRequestStateException(ReturnRequestStatus from, String message) {
        super(ErrorCode.INVALID_STATE, from == null ? message : message + " (현재 신청 상태=" + from + ")");
        this.from = from;
    }

    public ReturnRequestStatus getFrom() {
        return from;
    }
}
