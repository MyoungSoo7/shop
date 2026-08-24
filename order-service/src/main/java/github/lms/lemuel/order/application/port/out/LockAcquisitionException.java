package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 분산 락을 대기 시간 내에 획득하지 못했을 때 발생.
 *
 * <p>경합이 심해 동일 키 작업이 지연되는 상황 — 재시도 가능. {@link ErrorCode#LOCK_TIMEOUT}(409)로 매핑된다.
 */
public class LockAcquisitionException extends BusinessException {

    public LockAcquisitionException(String message) {
        super(ErrorCode.LOCK_TIMEOUT, message);
    }

    public LockAcquisitionException(String message, Throwable cause) {
        super(ErrorCode.LOCK_TIMEOUT, message, cause);
    }
}
