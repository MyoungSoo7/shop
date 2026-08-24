package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.application.port.out.ClaimOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class OutboxEventPersistenceAdapter implements SaveOutboxEventPort, LoadOutboxEventPort, ClaimOutboxEventPort {

    private final SpringDataOutboxEventRepository repository;
    /** N4 봉투의 producer — 도메인은 자기 배포 이름을 모르므로 어댑터 경계에서 채운다. */
    private final String producer;

    public OutboxEventPersistenceAdapter(SpringDataOutboxEventRepository repository,
                                         @Value("${spring.application.name:unknown}") String producer) {
        this.repository = repository;
        this.producer = producer;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        OutboxEventJpaEntity saved = repository.save(toEntity(event));
        return toDomain(saved);
    }

    @Override
    @Transactional
    public void saveAll(List<OutboxEvent> events) {
        repository.saveAll(events.stream().map(this::toEntity).toList());
    }

    @Override
    @Transactional
    public List<OutboxEvent> claimPending(int limit, Duration lease, String worker) {
        List<Long> ids = repository.selectClaimableIds(limit, lease.toSeconds());
        if (ids.isEmpty()) {
            return List.of();
        }
        repository.stampClaim(ids, worker, LocalDateTime.now());
        return repository.findByIdInOrderByCreatedAtAsc(ids).stream()
                .map(OutboxEventPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void releaseClaim(List<Long> ids) {
        if (!ids.isEmpty()) {
            repository.clearClaim(ids);
        }
    }

    @Override
    public long countPending() {
        return repository.countByStatus(OutboxEventStatus.PENDING);
    }

    @Override
    public List<OutboxEvent> findFailed(int offset, int limit) {
        int page = limit > 0 ? offset / limit : 0;
        return repository
                .findByStatusOrderByCreatedAtAsc(OutboxEventStatus.FAILED, PageRequest.of(page, limit))
                .stream()
                .map(OutboxEventPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public long countFailed() {
        return repository.countByStatus(OutboxEventStatus.FAILED);
    }

    @Override
    public Optional<OutboxEvent> findByEventId(UUID eventId) {
        return repository.findByEventId(eventId).map(OutboxEventPersistenceAdapter::toDomain);
    }

    private OutboxEventJpaEntity toEntity(OutboxEvent event) {
        return new OutboxEventJpaEntity(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getEventId(),
                event.getPayload(),
                event.getStatus(),
                event.getRetryCount(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getPublishedAt(),
                event.getTraceParent(),
                event.getOccurredAt(),
                event.getEventVersion(),
                event.getProducer() != null ? event.getProducer() : producer
        );
    }

    private static OutboxEvent toDomain(OutboxEventJpaEntity e) {
        return OutboxEvent.rehydrate(
                e.getId(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getEventType(),
                e.getEventId(),
                e.getPayload(),
                e.getStatus(),
                e.getRetryCount(),
                e.getLastError(),
                e.getCreatedAt(),
                e.getPublishedAt(),
                e.getTraceParent(),
                // 마이그레이션 이전에 기록된 행 방어 — occurred_at 은 created_at 을 UTC 로 간주
                e.getOccurredAt() != null ? e.getOccurredAt() : e.getCreatedAt().atOffset(ZoneOffset.UTC),
                e.getEventVersion() > 0 ? e.getEventVersion() : OutboxEvent.DEFAULT_EVENT_VERSION,
                e.getProducer()
        );
    }
}
