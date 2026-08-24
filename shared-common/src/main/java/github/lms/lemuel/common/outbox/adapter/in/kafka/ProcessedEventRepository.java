package github.lms.lemuel.common.outbox.adapter.in.kafka;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEventJpaEntity, ProcessedEventJpaEntity.ProcessedEventId> {
    boolean existsById(ProcessedEventJpaEntity.ProcessedEventId id);
}
