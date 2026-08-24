package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 동일 Idempotency-Key 의 중복 주문 제출이 감지됐고(분산 락 우회 등) 기존 주문도 아직 조회되지 않는
 * 극히 드문 타이밍에 발생. 클라이언트 재시도 시 멱등 복원된다. {@link ErrorCode#DUPLICATE_ORDER_SUBMISSION}(409).
 */
public class DuplicateOrderSubmissionException extends BusinessException {

    public DuplicateOrderSubmissionException(String idempotencyKey) {
        super(ErrorCode.DUPLICATE_ORDER_SUBMISSION, "중복 주문 요청입니다(키=" + idempotencyKey + "). 잠시 후 다시 시도해주세요.");
    }
}
