package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 결제 도메인 불변식 위반 — 금액/필수값/분할결제 규칙을 어겼다
 * (tender 금액·sequence, 외부 PG tender 의 pgTransactionId, 환불 금액·초과, 분할결제 최소 지불수단).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 상태 전이 위반은 {@link InvalidPaymentStateException} 가 담당한다.
 */
public class PaymentInvariantViolationException extends BusinessException {

    public PaymentInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
