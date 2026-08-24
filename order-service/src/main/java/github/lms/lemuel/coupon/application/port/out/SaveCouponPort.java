package github.lms.lemuel.coupon.application.port.out;

import github.lms.lemuel.coupon.domain.Coupon;

import java.util.List;

public interface SaveCouponPort {
    Coupon save(Coupon coupon);
    void recordUsage(Long couponId, Long userId, Long orderId);

    /**
     * 사용 한도 내에서만 사용 횟수를 원자적으로 1 증가시킨다.
     * @return 증가에 성공하면 true, 이미 소진되었으면 false
     */
    boolean incrementUsageIfAvailable(Long couponId);

    /**
     * 주문이 쓴 쿠폰 사용 이력을 무효화한다 — 행을 지우지 않고 {@code revoked_at} 을 찍는 원장 보존형.
     *
     * <p>이미 무효화된 이력은 다시 잡히지 않으므로 <b>멱등</b>하다. 취소 승인·환불 콜백처럼 종단으로
     * 가는 경로가 여러 개라 중복 호출이 정상 상황이고, 여기서 멱등이 깨지면 사용 횟수가 실제보다
     * 여러 번 깎여 쿠폰 한도가 늘어난다.
     *
     * @return 이번 호출로 무효화된 사용 이력의 쿠폰 id 목록(중복 없음). 되돌릴 것이 없으면 빈 목록
     */
    List<Long> revokeUsagesForOrder(Long orderId, String reason);

    /**
     * 사용 횟수를 1 감소시킨다. {@code used_count > 0} 조건이 붙은 원자적 UPDATE 라
     * 동시 회수에서도 음수로 내려가지 않는다.
     *
     * @return 감소에 성공하면 true, 이미 0 이면 false
     */
    boolean decrementUsage(Long couponId);
}
