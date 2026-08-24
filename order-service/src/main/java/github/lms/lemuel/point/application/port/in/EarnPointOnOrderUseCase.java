package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 주문 확정 적립 유스케이스.
 *
 * <p>적립률 정책이 하나도 없으면 <b>아무 일도 하지 않는다</b>(적립액 0). 이 기능의 무행동 착지가
 * 여기서 나온다 — 정책 표를 채우기 전까지 주문 흐름은 도입 전과 동일하게 동작한다.
 *
 * <p>멱등은 원장 자연키가 보장한다: 같은 주문으로 두 번 호출해도
 * {@code (account, ORDER_EARN, ORDER, orderId)} 로트가 한 번만 발급된다.
 */
public interface EarnPointOnOrderUseCase {

    /**
     * @param eligibleAmount 적립 대상 금액 — 배송비를 뺀 상품 금액이다(호출자가 계산해 넘긴다)
     * @param on             정책 해석 기준일
     */
    record EarnPointCommand(Long orderId, Long userId, BigDecimal eligibleAmount,
                            LocalDate on, String actor) {
    }

    /** {@code earnedAmount} 가 0 이면 적립이 일어나지 않았다는 뜻(정책 없음 또는 1원 미만). */
    record EarnPointResult(BigDecimal earnedAmount, Long lotId) {
    }

    EarnPointResult earn(EarnPointCommand command);
}
