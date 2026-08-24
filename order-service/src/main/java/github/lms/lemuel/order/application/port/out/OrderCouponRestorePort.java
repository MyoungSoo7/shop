package github.lms.lemuel.order.application.port.out;

/**
 * 주문이 종단(취소·환불)에 이르렀을 때 쿠폰을 되돌려 달라고 부르는 아웃바운드 포트.
 *
 * <p>{@link OrderPointRewardPort} 와 같은 모양이다 — 주문 도메인은 "언제"만 알고, 쿠폰 한도·1인 1매·
 * 사용 이력을 어떻게 되돌릴지는 coupon 도메인이 정한다. 그래서 시그니처에 쿠폰 코드도 할인액도 없다.
 *
 * <p>연산은 <b>멱등</b>이다. 종단으로 가는 경로가 여러 개(관리자 취소 승인, 환불 승인, PG 환불 콜백)라
 * 중복 호출이 정상 상황이다.
 */
public interface OrderCouponRestorePort {

    /** 취소·환불된 주문이 사용한 쿠폰을 되돌린다. 쿠폰을 쓰지 않은 주문이면 아무 일도 하지 않는다. */
    void restoreOnCanceled(Long orderId, String reason);
}
