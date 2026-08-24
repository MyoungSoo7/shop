package github.lms.lemuel.payment.application.port.out;

import java.util.Optional;

/**
 * 결제의 소유자(주문자) 조회 — <b>IDOR 방지 전용</b>.
 *
 * <p>현금영수증은 이름·휴대폰번호·사업자번호가 붙는 세금 서류다. "결제 id 만 알면 조회/발급"이면
 * 남의 결제로 내 소득공제를 받거나 남의 식별번호를 들여다볼 수 있다. 그래서 요청자는 JWT 주체에서
 * 오고, 이 포트가 돌려준 소유자와 <b>대조</b>한 뒤에만 진행한다.
 *
 * <p>결제 자체는 userId 를 갖지 않는다(주문이 갖는다). 조인 한 번이면 되는 조회라 별도 포트로 둔다 —
 * 이걸 위해 {@code PaymentDomain} 에 userId 를 복사해 넣으면 두 곳이 어긋날 자리가 생긴다.
 */
public interface LoadPaymentOwnerPort {

    /** 결제 → 주문 → 주문자 userId. 결제나 주문이 없으면 빈 값. */
    Optional<Long> findOwnerUserId(Long paymentId);
}
