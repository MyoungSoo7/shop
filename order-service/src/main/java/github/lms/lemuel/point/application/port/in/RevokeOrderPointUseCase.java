package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;

/**
 * 주문 적립 회수 유스케이스 — 적립의 대칭 연산.
 *
 * <p>적립만 붙이고 회수를 두지 않으면 새 구멍이 생긴다: 배송 완료로 적립을 받은 뒤 환불하면
 * 상품값은 돌려받고 적립 포인트는 그대로 남는다.
 *
 * <p>회수 대상 계정은 커맨드에 없다 — 적립 엔트리에서 도출한다. 이미 다 써 버렸거나 소멸한
 * 적립분은 회수할 수 없으며(잔고를 음수로 만들지 않는다), 그때는 회수액 0 으로 끝난다.
 */
public interface RevokeOrderPointUseCase {

    record RevokeOrderPointCommand(Long orderId, String actor) {
    }

    /** {@code revokedAmount} 가 0 이면 회수할 적립분이 없었다는 뜻(미적립·전액 사용·소멸). */
    record RevokeOrderPointResult(BigDecimal revokedAmount) {
    }

    RevokeOrderPointResult revoke(RevokeOrderPointCommand command);
}
