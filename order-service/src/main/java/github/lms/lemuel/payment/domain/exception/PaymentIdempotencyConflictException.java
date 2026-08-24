package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 같은 멱등 키가 <b>다른 요청</b>에 다시 쓰였을 때 던진다.
 *
 * <p>멱등의 정의는 "같은 요청을 다시 보내면 같은 결과"다. 요청이 달라졌는데도 저장된 결과를
 * 돌려주면 그것은 멱등이 아니라 <b>오답</b>이다 — 주문 A 로 만든 키를 주문 B 에 쓰면 B 는
 * 결제되지 않았는데 성공 응답을 받고, 화면은 A 의 결제를 B 의 것으로 표시한다.
 *
 * <p>그래서 replay 전에 저장된 결제가 이번 요청과 같은 주문을 가리키는지 대조하고, 어긋나면
 * 조용히 통과시키는 대신 409 로 드러낸다. 클라이언트가 키를 새로 만들어 재시도하면 된다.
 */
public class PaymentIdempotencyConflictException extends BusinessException {

    public PaymentIdempotencyConflictException(String key, Long storedOrderId, Long requestedOrderId) {
        super(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT,
                "이미 다른 요청에 사용된 멱등 키입니다: storedOrderId=" + storedOrderId
                        + ", requestedOrderId=" + requestedOrderId);
    }
}
