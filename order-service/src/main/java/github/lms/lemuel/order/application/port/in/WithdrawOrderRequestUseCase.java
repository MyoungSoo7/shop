package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

/**
 * 취소·환불 <b>신청 철회</b> — 신청 상태에서 신청 직전 상태로 되돌린다.
 *
 * <p>신청 상태에서 나가는 길이 승인뿐이면 마음이 바뀐 고객의 주문이 운영자 처리까지 묶인다.
 * 실무 커머스가 항상 제공하는 정상 경로다.
 */
public interface WithdrawOrderRequestUseCase {

    Order withdraw(Long orderId, String reason, String operator);
}
