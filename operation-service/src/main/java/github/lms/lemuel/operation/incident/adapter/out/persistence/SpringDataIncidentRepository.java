package github.lms.lemuel.operation.incident.adapter.out.persistence;

import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataIncidentRepository
        extends JpaRepository<IncidentJpaEntity, Long>, JpaSpecificationExecutor<IncidentJpaEntity> {

    Optional<IncidentJpaEntity> findFirstBySourceAndCorrelationKeyAndStatusIn(
            IncidentSource source, String correlationKey, Collection<IncidentStatus> statuses);

    long countByStatusIn(Collection<IncidentStatus> statuses);

    @Query("select i.status, count(i) from IncidentJpaEntity i where i.firstSeenAt >= :from group by i.status")
    List<Object[]> countByStatusSince(@Param("from") Instant from);

    @Query("select i.category, count(i) from IncidentJpaEntity i where i.firstSeenAt >= :from group by i.category")
    List<Object[]> countByCategorySince(@Param("from") Instant from);

    @Query("select i.severity, count(i) from IncidentJpaEntity i where i.firstSeenAt >= :from group by i.severity")
    List<Object[]> countBySeveritySince(@Param("from") Instant from);

    /**
     * window 내 RESOLVED 건의 평균 해소 시간(초). 해당 건 없으면 null.
     *
     * <p>네이티브 쿼리는 hibernate default_schema 의 적용을 받지 않으므로 opslab 을 직접 명시
     * (shared-common Outbox 네이티브 쿼리와 동일 관례 — 물리 DB 는 lemuel_operation 으로 분리됨).
     */
    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - first_seen_at)))
            FROM opslab.incidents
            WHERE status = 'RESOLVED' AND resolved_at >= :from
            """, nativeQuery = true)
    Double averageResolutionSeconds(@Param("from") Instant from);
}
