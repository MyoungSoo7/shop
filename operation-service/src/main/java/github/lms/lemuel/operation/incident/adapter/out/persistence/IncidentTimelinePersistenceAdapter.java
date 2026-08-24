package github.lms.lemuel.operation.incident.adapter.out.persistence;

import github.lms.lemuel.operation.incident.application.port.out.RecordTimelinePort;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IncidentTimelinePersistenceAdapter implements RecordTimelinePort {

    private final SpringDataIncidentTimelineRepository repository;

    public IncidentTimelinePersistenceAdapter(SpringDataIncidentTimelineRepository repository) {
        this.repository = repository;
    }

    @Override
    public IncidentTimelineEntry record(IncidentTimelineEntry entry) {
        return repository.save(IncidentTimelineJpaEntity.fromDomain(entry)).toDomain();
    }

    @Override
    public List<IncidentTimelineEntry> findByIncidentId(Long incidentId) {
        return repository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(IncidentTimelineJpaEntity::toDomain)
                .toList();
    }
}
