package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 환불 처리 도메인 예외 (payment 도메인 소유).
 *
 * <p>이전에는 shared-common 에 있었으나, 환불은 결제 도메인 개념이고 order-service 만 사용하므로
 * payment 도메인으로 이전했다 (다른 서비스로의 도메인 누수 제거 — MSA/DDD 경계 정합).
 *
 * <p>{@link BusinessException} 을 상속하며 기본 코드는 {@link ErrorCode#REFUND_ERROR}(500).
 * 하위 예외는 protected 생성자로 자신의 {@link ErrorCode} 를 전달한다.
 */
public class RefundException extends BusinessException {
    public RefundException(String message) {
        super(ErrorCode.REFUND_ERROR, message);
    }

    public RefundException(String message, Throwable cause) {
        super(ErrorCode.REFUND_ERROR, message, cause);
    }

    protected RefundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
