package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointLotStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PointLotRepository extends JpaRepository<PointLotJpaEntity, Long> {

    /** 소비 가능한 로트. 정렬은 도메인(PointLotSelector)이 책임지므로 순서를 약속하지 않는다. */
    @Query("""
            select l from PointLotJpaEntity l
            where l.accountId = :accountId and l.status = :status and l.remainingAmount > 0
            """)
    List<PointLotJpaEntity> findConsumable(@Param("accountId") Long accountId,
                                           @Param("status") PointLotStatus status);

    /** 소멸 배치 스캔 — 계정별로 묶어 처리할 수 있게 accountId 순으로 돌려준다. */
    @Query("""
            select l from PointLotJpaEntity l
            where l.status = :status and l.expiresAt is not null and l.expiresAt < :at
            order by l.accountId asc, l.id asc
            """)
    List<PointLotJpaEntity> findExpired(@Param("status") PointLotStatus status,
                                        @Param("at") OffsetDateTime at,
                                        Pageable pageable);

    List<PointLotJpaEntity> findByIdIn(Collection<Long> ids);
}
