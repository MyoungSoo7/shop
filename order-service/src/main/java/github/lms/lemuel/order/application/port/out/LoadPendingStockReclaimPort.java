package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;

import java.util.List;

/**
 * 회수 대기 후보 조회 포트.
 *
 * <p>어댑터는 인덱스로 좁힐 수 있는 조건(배송됨 · 미원복 · 종단)까지만 거른다 — 최종 판정은
 * {@link Order#isAwaitingStockReclaim()} 가 하므로, 쿼리 조건이 도메인 규칙과 어긋나도
 * 잘못된 건이 화면에 오르지 않는다.
 */
public interface LoadPendingStockReclaimPort {

    List<Order> findAwaitingStockReclaim(int limit);

    /**
     * 회수 지연 임계를 <b>갓 넘긴</b> 구간의 대기 건 — 지연 신호 발행 전용.
     *
     * <p>전체 대기 건이 아니라 {@code (from, to]} 구간만 돌려준다. 매 주기 전량을 훑으면 같은 건이
     * 계속 재발행돼 인시던트가 노이즈가 된다.
     */
    List<Order> findStockReclaimCrossedBetween(java.time.LocalDateTime from,
                                               java.time.LocalDateTime to, int limit);
}
