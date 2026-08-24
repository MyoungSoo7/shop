package github.lms.lemuel.coupon.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataCouponUsageJpaRepository extends JpaRepository<CouponUsageJpaEntity, Long> {

    /**
     * 1인 1매 판정 — <b>무효화되지 않은</b> 사용만 센다. 주문 취소로 되돌려 준 쿠폰은 다시 쓸 수
     * 있어야 하므로 revoked 행까지 세면 고객이 환불받고도 쿠폰을 잃는다.
     */
    boolean existsByCouponIdAndUserIdAndRevokedAtIsNull(Long couponId, Long userId);

    @Query("SELECT DISTINCT u.couponId FROM CouponUsageJpaEntity u "
            + "WHERE u.orderId = :orderId AND u.revokedAt IS NULL")
    List<Long> findActiveCouponIdsByOrderId(@Param("orderId") Long orderId);

    /**
     * 주문의 살아 있는 사용 이력을 한 번에 무효화한다. {@code revokedAt IS NULL} 조건이 멱등을 만든다 —
     * 두 번째 호출은 영향 행 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CouponUsageJpaEntity u SET u.revokedAt = CURRENT_TIMESTAMP, u.revokeReason = :reason "
            + "WHERE u.orderId = :orderId AND u.revokedAt IS NULL")
    int revokeByOrderId(@Param("orderId") Long orderId, @Param("reason") String reason);
}
