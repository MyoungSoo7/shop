package github.lms.lemuel.giftcard.application.port.in;

import java.math.BigDecimal;

/**
 * 기프트카드 선점 유스케이스 — 입금 대기 결제가 부르는 진입점.
 *
 * <p>포인트와 같은 모양이지만 한 근거가 <b>카드 여러 장</b>에 걸칠 수 있다(권면가 단위로 발행되어
 * 한 장으로 못 채우는 경우). 확정·해제는 그 여러 장을 한 번에 다룬다.
 *
 * <p>{@code userId} 는 반드시 <b>JWT 주체에서 파생</b>한 값이어야 한다(IDOR).
 */
public interface HoldGiftCardUseCase {

    record HoldCommand(Long userId, BigDecimal amount, String referenceType, String referenceId) {
    }

    record HoldResult(int cardCount, BigDecimal heldAmount) {
    }

    /** 만료 임박 순으로 카드를 골라 잠근다. 같은 근거로 이미 잠갔으면 그대로 돌려준다(멱등). */
    HoldResult hold(HoldCommand command);

    /**
     * 입금이 확인돼 잠근 몫을 실제로 쓴다 — 카드 잔액을 깎고 {@code USE} 엔트리를 남긴다.
     *
     * <p>선점이 없으면 <b>예외</b>다. 조용히 넘기면 고객 상품권을 받지 않은 채 주문이 확정된다.
     */
    void capture(String referenceType, String referenceId, String actor);

    /**
     * 잠금을 푼다. 카드 잔액은 애초에 건드리지 않았으므로 선점 행의 상태만 바뀐다.
     *
     * <p>선점이 없으면 경고만 남긴다 — 이 경로는 미입금 만료 배치가 부르는데, 여기서 막으면
     * 재고 회수까지 함께 멈춘다.
     *
     * @param expired 기한 경과로 자동 해제면 {@code true}, 주문 취소 등 명시적 해제면 {@code false}
     */
    void release(String referenceType, String referenceId, boolean expired);
}
