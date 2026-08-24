package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;

/**
 * 포인트 선점 유스케이스 — 입금 대기 결제가 부르는 진입점.
 *
 * <p>세 연산의 호출자가 서로 다르다: 선점은 <b>결제 생성</b>, 확정은 <b>입금 확인</b>,
 * 해제는 <b>주문 취소·미입금 만료 배치</b>다. 그래서 확정·해제는 사용자 식별자가 아니라
 * <b>근거(tender)</b> 로만 받는다 — 호출자가 넘긴 계정을 믿고 잠금을 풀면 남의 계정 잠금을
 * 푸는 통로가 된다.
 *
 * <p>{@code userId} 는 반드시 <b>JWT 주체에서 파생</b>한 값이어야 한다(IDOR).
 */
public interface HoldPointUseCase {

    /**
     * @param userId        포인트 소유자 — JWT 주체에서 파생
     * @param amount        선점액(양수, 1원 단위 정수)
     * @param referenceType 선점 근거 종류(예: {@code PAYMENT_TENDER})
     * @param referenceId   선점 근거 식별자(예: tenderId)
     */
    record HoldCommand(Long userId, BigDecimal amount, String referenceType, String referenceId) {
    }

    record HoldResult(Long holdId, BigDecimal heldAmount, BigDecimal remainingAvailable) {
    }

    /** 가용에서 빼서 잠근다. 같은 근거로 이미 선점했으면 그 선점을 그대로 돌려준다(멱등). */
    HoldResult hold(HoldCommand command);

    /**
     * 입금이 확인돼 잠근 몫을 실제로 쓴다 — 로트를 소비하고 USE 엔트리를 남긴다.
     *
     * <p>선점이 없으면 <b>예외</b>다. 조용히 넘기면 고객 포인트를 받지 않은 채 주문이 확정된다.
     */
    void capture(String referenceType, String referenceId, String actor);

    /**
     * 잠금을 풀어 가용으로 되돌린다.
     *
     * <p>선점이 없으면 경고만 남기고 넘어간다 — 이 경로는 미입금 만료 배치가 부르는데, 여기서
     * 막으면 재고 회수까지 함께 멈춘다. 없는 선점을 푸는 것은 아무 해가 없다.
     *
     * @param expired 기한 경과로 자동 해제면 {@code true}, 주문 취소 등 명시적 해제면 {@code false}.
     *                잔고 효과는 같지만 <b>왜 풀렸는지</b>가 운영 판단을 가른다
     */
    void release(String referenceType, String referenceId, boolean expired);
}
