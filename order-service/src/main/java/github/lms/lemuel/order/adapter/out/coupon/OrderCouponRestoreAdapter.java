package github.lms.lemuel.order.adapter.out.coupon;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.order.application.port.out.OrderCouponRestorePort;
import org.springframework.stereotype.Component;

/**
 * 주문 ↔ 쿠폰 회수 연결 어댑터.
 *
 * <p><b>취소 트랜잭션 안에서 함께 커밋된다</b>(예외를 삼키지 않는다). 쿠폰 회수는 외부 시스템 호출이
 * 아니라 우리 DB 두 테이블의 조건부 UPDATE 뿐이라, 여기서 실패한다는 것은 무결성 문제 그 자체다.
 * 반대로 별도 트랜잭션으로 떼어 내면 취소가 나중에 롤백됐을 때 "주문은 살아 있는데 쿠폰은 돌려준"
 * 상태가 남아 같은 할인을 두 번 쓸 수 있다 — 조용한 금액 누수다. 그래서 운명을 취소와 묶는다.
 */
@Component
public class OrderCouponRestoreAdapter implements OrderCouponRestorePort {

    private final CouponUseCase couponUseCase;

    public OrderCouponRestoreAdapter(CouponUseCase couponUseCase) {
        this.couponUseCase = couponUseCase;
    }

    @Override
    public void restoreOnCanceled(Long orderId, String reason) {
        if (orderId == null) {
            return;
        }
        couponUseCase.restoreCouponsForOrder(orderId, reason);
    }
}
