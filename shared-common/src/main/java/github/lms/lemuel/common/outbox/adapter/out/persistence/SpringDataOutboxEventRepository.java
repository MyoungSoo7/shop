package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbox 영속 리포지토리. 스키마 한정자를 런타임 주입해야 하는 SKIP LOCKED 폴링 쿼리는
 * {@link SpringDataOutboxEventRepositoryCustom} 프래그먼트(네이티브 SQL 직접 조립)에 위임한다.
 * 네이티브 {@code @Query} 의 {@code #{...}} SpEL 은 구조적 위치에서 평가되지 않아 사용하지 않는다.
 */
public interface SpringDataOutboxEventRepository
        extends JpaRepository<OutboxEventJpaEntity, Long>, SpringDataOutboxEventRepositoryCustom {

    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(@Param("status") OutboxEventStatus status, Pageable pageable);

    long countByStatus(OutboxEventStatus status);

    Optional<OutboxEventJpaEntity> findByEventId(UUID eventId);

    List<OutboxEventJpaEntity> findByIdInOrderByCreatedAtAsc(List<Long> ids);
}
