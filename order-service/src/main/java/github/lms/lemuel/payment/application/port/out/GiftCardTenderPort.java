package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * 결제가 기프트카드 잔액을 움직일 때 쓰는 아웃바운드 포트.
 *
 * <p>{@code PointTenderPort} 와 같은 모양이다. 결제 모듈은 카드가 몇 장인지, 어느 장부터 쓰는지
 * 알지 않는다 — 그건 giftcard 도메인의 몫이다.
 *
 * <p>참조 규약: 사용은 {@code PAYMENT_TENDER:{tenderId}}, 환불은
 * {@code PAYMENT_TENDER_REFUND:{idempotencyKey}}.
 */
public interface GiftCardTenderPort {

    /**
     * 결제 시 상품권 차감. 잔액이 모자라면 예외가 올라와 결제 트랜잭션이 롤백된다.
     *
     * @param userId 결제 주체 — JWT 에서 파생된 값이어야 한다(요청 본문을 믿지 마라)
     */
    void use(Long userId, BigDecimal amount, Long tenderId);

    /**
     * 환불 시 상품권 복원. 복원 대상 카드는 원장이 알고 있으므로 userId 를 받지 않는다 —
     * 환불은 언제나 낸 사람에게 돌아가야 한다.
     */
    void restore(BigDecimal amount, Long tenderId, String refundReference);

    /**
     * 입금 대기 결제의 상품권 <b>선점</b> — 만료 임박 순으로 카드를 골라 잠근다.
     *
     * <p>포인트와 달리 <b>카드 잔액을 건드리지 않는다</b>. 기프트카드는 잠긴 금액을 저장하지 않고
     * 가용액을 계산으로 구하므로, 선점 시점에는 선점 행만 생긴다.
     */
    void hold(Long userId, BigDecimal amount, Long tenderId);

    /** 입금이 확인돼 선점을 실제 차감으로 확정한다 — 여기서 카드가 깎이고 USE 엔트리가 남는다. */
    void captureHold(Long tenderId, Long actorUserId);

    /**
     * 선점을 푼다. 카드 잔액은 애초에 건드리지 않았으므로 선점 상태만 바뀐다.
     *
     * @param expired 기한 경과로 자동 해제면 {@code true}
     */
    void releaseHold(Long tenderId, boolean expired);
}
