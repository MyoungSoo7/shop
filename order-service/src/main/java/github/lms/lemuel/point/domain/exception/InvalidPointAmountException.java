package github.lms.lemuel.point.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import java.math.BigDecimal;

/**
 * 포인트 금액이 도메인 규약을 어겼을 때 (BusinessException 상속 — ErrorCode 가 HTTP 상태로 번역한다).
 *
 * <p>두 규약을 강제한다: <b>양수</b>여야 하고, <b>1원 단위 정수</b>여야 한다.
 * 소수 포인트가 한 번이라도 유입되면 이후 적립 절사·소멸 정산이 전부 미세하게 어긋난다.
 */
public class InvalidPointAmountException extends BusinessException {

    private final String operation;
    private final BigDecimal amount;

    public InvalidPointAmountException(String message, String operation, BigDecimal amount) {
        super(ErrorCode.POINT_INVALID_AMOUNT, message);
        this.operation = operation;
        this.amount = amount;
    }

    public String getOperation() { return operation; }
    public BigDecimal getAmount() { return amount; }
}
