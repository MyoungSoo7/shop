package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * 결제가 포인트 잔액을 움직일 때 쓰는 아웃바운드 포트.
 *
 * <p>결제 모듈은 포인트 원장의 내부를 알지 않는다 — 로트·엔트리·소비 순서는 point 도메인의 몫이고,
 * 여기서는 "이 tender 만큼 쓰고, 환불되면 되돌린다"만 표현한다.
 *
 * <p>참조 규약: 사용은 {@code PAYMENT_TENDER:{tenderId}}, 환불은
 * {@code PAYMENT_TENDER_REFUND:{idempotencyKey}}. 환불 키가 tender 와 금액을 함께 담고 있어
 * 같은 부분환불을 두 번 반영하는 일을 원장 자연키가 막는다.
 */
public interface PointTenderPort {

    /**
     * 결제 시 포인트 차감. 잔액이 모자라면 예외가 올라와 결제 트랜잭션이 롤백된다 —
     * 검증 없이 통과시키는 것보다 결제를 실패시키는 편이 옳다.
     *
     * @param userId   결제 주체 — JWT 에서 파생된 값이어야 한다(요청 본문을 믿지 마라)
     * @param tenderId 사용 근거가 되는 tender 식별자
     */
    void use(Long userId, BigDecimal amount, Long tenderId);

    /**
     * 이 결제에서 쓰려는 포인트 총액이 주문당 사용 상한 안인지 검사한다.
     *
     * <p>상한은 결제 금액에 대한 규칙이라 포인트 원장 단독으로는 판단할 수 없고(주문 금액을 모른다),
     * 그렇다고 결제가 정책을 해석하면 규칙이 두 곳에 생긴다 — 금액을 넘겨 포인트가 판단하게 한다.
     * 상한 초과면 예외가 올라와 결제 트랜잭션이 시작되기 전에 멈춘다(PG 호출 전에 끊는 것이 중요하다).
     */
    void assertWithinUsageLimit(BigDecimal orderAmount, BigDecimal pointAmount);

    /**
     * 환불 시 포인트 복원. 복원 대상 계정은 원장이 알고 있으므로 userId 를 받지 않는다 —
     * 환불은 언제나 <b>낸 사람</b>에게 돌아가야 한다.
     *
     * @param refundReference 환불 멱등 키(tender + 금액)
     */
    void restore(BigDecimal amount, Long tenderId, String refundReference);

    /**
     * 입금 대기 결제의 포인트 <b>선점</b> — 가용에서 빼서 잠근다. 총액은 아직 줄지 않는다.
     *
     * <p>가상계좌는 입금 전까지 결제가 확정되지 않는다. 그 사이 차감하지 않으면 같은 포인트를
     * 다른 주문에 또 쓸 수 있고, 차감해 버리면 미입금 취소마다 복원 경로가 필요하다.
     *
     * @param userId 결제 주체 — JWT 에서 파생된 값이어야 한다
     */
    void hold(Long userId, BigDecimal amount, Long tenderId);

    /**
     * 입금이 확인돼 선점을 실제 차감으로 확정한다. 여기서 비로소 로트가 소비되고 USE 엔트리가 남는다.
     *
     * <p>{@code userId} 를 받지 않는다 — 계정은 선점 레코드가 알고 있다. 호출자가 넘긴 계정을 믿고
     * 잠금을 건드리면 남의 계정을 움직이는 통로가 된다.
     */
    void captureHold(Long tenderId, Long actorUserId);

    /**
     * 선점을 풀어 가용으로 되돌린다.
     *
     * @param expired 기한 경과로 자동 해제면 {@code true}, 주문 취소 등 명시적 해제면 {@code false}
     */
    void releaseHold(Long tenderId, boolean expired);
}
