package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.incident.application.port.in.IncidentNotFoundException;
import github.lms.lemuel.operation.incident.application.port.in.IncidentQuery;
import github.lms.lemuel.operation.incident.application.port.out.LoadIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.RecordTimelinePort;
import github.lms.lemuel.operation.incident.application.port.out.SearchIncidentPort;
import github.lms.lemuel.operation.incident.domain.Incident;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class IncidentQueryService implements IncidentQuery {

    private final LoadIncidentPort loadIncidentPort;
    private final SearchIncidentPort searchIncidentPort;
    private final RecordTimelinePort recordTimelinePort;
    private final Clock clock;

    public IncidentQueryService(LoadIncidentPort loadIncidentPort, SearchIncidentPort searchIncidentPort,
                                RecordTimelinePort recordTimelinePort, Clock clock) {
        this.loadIncidentPort = loadIncidentPort;
        this.searchIncidentPort = searchIncidentPort;
        this.recordTimelinePort = recordTimelinePort;
        this.clock = clock;
    }

    @Override
    public PageResult<Incident> search(IncidentSearchCondition condition) {
        return searchIncidentPort.search(condition);
    }

    @Override
    public IncidentDetail get(Long incidentId) {
        Incident incident = loadIncidentPort.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
        return new IncidentDetail(incident, recordTimelinePort.findByIncidentId(incidentId));
    }

    @Override
    public IncidentSummary summary(Duration window) {
        Instant from = Instant.now(clock).minus(window);
        return searchIncidentPort.summarize(from);
    }
}
