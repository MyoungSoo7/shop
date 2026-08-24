package github.lms.lemuel.operation.incident.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataIncidentTimelineRepository extends JpaRepository<IncidentTimelineJpaEntity, Long> {

    List<IncidentTimelineJpaEntity> findByIncidentIdOrderByCreatedAtAsc(Long incidentId);
}
