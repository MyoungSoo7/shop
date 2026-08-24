package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;

/**
 * 주문 상태 전이가 포인트 적립/회수를 부를 때 쓰는 아웃바운드 포트.
 *
 * <p>주문 도메인은 적립률도 로트도 모른다 — "언제"(배송 완료 / 취소·환불)만 알고, "얼마를 어떻게"는
 * point 도메인이 정한다. 그래서 이 포트의 시그니처에 금액이나 정책이 등장하지 않는다.
 *
 * <p>두 연산 모두 <b>멱등</b>이다. 같은 주문으로 여러 번 불려도 원장 자연키가 한 번만 반영한다 —
 * 상태 전이 경로가 여러 개(관리자 승인, 결제 환불 콜백)라 중복 호출은 정상 상황이다.
 */
public interface OrderPointRewardPort {

    /** 배송 완료로 확정된 주문에 적립한다. 정책이 없거나 적립액이 1원 미만이면 아무 일도 하지 않는다. */
    void earnOnDelivered(Order order);

    /** 취소·환불된 주문의 적립분을 회수한다. 적립이 없었거나 이미 다 쓰였으면 아무 일도 하지 않는다. */
    void revokeOnCanceled(Order order);
}
