package github.lms.lemuel.coupon.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataCouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {
    Optional<CouponJpaEntity> findByCode(String code);

    /**
     * 사용 한도 내에서만 사용 횟수를 1 증가시키는 원자적 UPDATE.
     * 동시 요청에서도 used_count <= max_uses 불변식을 DB가 보장한다.
     * 영향 행 수가 0이면 이미 소진된 것.
     */
    @Modifying
    @Query("UPDATE CouponJpaEntity c SET c.usedCount = c.usedCount + 1, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.usedCount < c.maxUses")
    int incrementUsedCountIfAvailable(@Param("id") Long id);

    /**
     * 사용 횟수를 1 감소시키는 원자적 UPDATE(주문 취소·환불 회수).
     * {@code used_count > 0} 조건이 동시 회수에서도 음수 사용 횟수를 막는다 — 음수가 되면
     * {@code used_count < max_uses} 한도가 영구히 헐거워진다. 영향 행 수가 0 이면 되돌릴 사용이 없다.
     */
    @Modifying
    @Query("UPDATE CouponJpaEntity c SET c.usedCount = c.usedCount - 1, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.usedCount > 0")
    int decrementUsedCount(@Param("id") Long id);
}