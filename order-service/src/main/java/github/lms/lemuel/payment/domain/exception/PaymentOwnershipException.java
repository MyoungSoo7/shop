package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 결제하려는 주문이 인증 주체 본인의 것이 아닐 때 던진다(IDOR 차단).
 *
 * <p>가드레일 "사용자 리소스 식별자를 요청 파라미터로 신뢰 금지"의 결제 경로 적용. 요청 본문의
 * {@code dbOrderId} 를 그대로 믿으면 남의 주문번호만 알아도 그 주문을 결제 완료 상태로 만들 수
 * 있다. 웹 어댑터의 {@code ResourceOwnership} 과 같은 정책이되, 대조 대상(주문 소유자)이 DB 를
 * 읽어야 나오므로 판정 지점이 서비스 계층이다 — 그래서 {@code AccessDeniedException} 이 아니라
 * 도메인 예외로 두어 애플리케이션 계층이 스프링 시큐리티에 의존하지 않게 한다.
 */
public class PaymentOwnershipException extends BusinessException {

    public PaymentOwnershipException(Long orderId) {
        super(ErrorCode.ACCESS_DENIED, "본인 소유가 아닌 주문입니다: orderId=" + orderId);
    }
}
