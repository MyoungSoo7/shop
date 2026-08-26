package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataGiftClaimRepository extends JpaRepository<GiftClaimJpaEntity, Long> {

    /** 평문이 아니라 해시로 찾는다 — 평문 조회 메서드는 일부러 두지 않는다. */
    Optional<GiftClaimJpaEntity> findByTokenHash(String tokenHash);

    Optional<GiftClaimJpaEntity> findByOrderId(Long orderId);

    /**
     * 기한이 지났는데 아직 열려 있는 링크. 상태 문자열을 직접 적는 것은
     * {@link SpringDataOrderReturnRequestRepository#findOpenByOrderId} 와 같은 관례다.
     */
    @Query("""
            SELECT g FROM GiftClaimJpaEntity g
            WHERE g.status IN ('PENDING', 'VERIFIED') AND g.expiresAt < :now
            ORDER BY g.expiresAt ASC
            """)
    List<GiftClaimJpaEntity> findExpirable(@Param("now") LocalDateTime now, Pageable pageable);
}
