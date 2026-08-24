package github.lms.lemuel.operation.incident.application.port.out;

import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;

import java.util.List;

public interface RecordTimelinePort {

    IncidentTimelineEntry record(IncidentTimelineEntry entry);

    /** 인시던트의 타임라인 — created_at 오름차순. */
    List<IncidentTimelineEntry> findByIncidentId(Long incidentId);
}
