package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.incident.application.port.in.CommentIncidentUseCase;
import github.lms.lemuel.operation.incident.application.port.in.IncidentNotFoundException;
import github.lms.lemuel.operation.incident.application.port.in.TransitionIncidentUseCase;
import github.lms.lemuel.operation.incident.application.port.out.LoadIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.RecordTimelinePort;
import github.lms.lemuel.operation.incident.application.port.out.SaveIncidentPort;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import github.lms.lemuel.operation.incident.domain.TimelineEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 운영자 인시던트 조작 — 전이(ack/resolve/false-positive)와 코멘트.
 *
 * <p>전이 규칙은 도메인 상태머신이 강제하고, 운영자 간 동시 조작은 @Version 낙관적 락이 방어한다.
 * 타임라인이 감사 이력을 겸하므로 모든 조작은 타임라인 행을 남긴다.
 */
@Service
@Transactional
public class IncidentCommandService implements TransitionIncidentUseCase, CommentIncidentUseCase {

    private final LoadIncidentPort loadIncidentPort;
    private final SaveIncidentPort saveIncidentPort;
    private final RecordTimelinePort recordTimelinePort;
    private final Clock clock;

    public IncidentCommandService(LoadIncidentPort loadIncidentPort, SaveIncidentPort saveIncidentPort,
                                  RecordTimelinePort recordTimelinePort, Clock clock) {
        this.loadIncidentPort = loadIncidentPort;
        this.saveIncidentPort = saveIncidentPort;
        this.recordTimelinePort = recordTimelinePort;
        this.clock = clock;
    }

    @Override
    public Incident acknowledge(Long incidentId, String actor, String note) {
        Instant now = Instant.now(clock);
        Incident incident = load(incidentId);
        incident.acknowledge(actor, now);
        return saveWithTimeline(incident, TimelineEventType.ACKNOWLEDGED, actor, note, now);
    }

    @Override
    public Incident resolve(Long incidentId, String actor, String note) {
        Instant now = Instant.now(clock);
        Incident incident = load(incidentId);
        incident.resolve(actor, now);
        return saveWithTimeline(incident, TimelineEventType.RESOLVED, actor, note, now);
    }

    @Override
    public Incident markFalsePositive(Long incidentId, String actor, String note) {
        Instant now = Instant.now(clock);
        Incident incident = load(incidentId);
        incident.markFalsePositive(actor, now);
        return saveWithTimeline(incident, TimelineEventType.FALSE_POSITIVE, actor, note, now);
    }

    @Override
    public IncidentTimelineEntry comment(Long incidentId, String actor, String note) {
        load(incidentId); // 존재 검증 (없으면 404)
        return recordTimelinePort.record(IncidentTimelineEntry.of(
                incidentId, TimelineEventType.COMMENT, actor, note, Instant.now(clock)));
    }

    private Incident load(Long incidentId) {
        return loadIncidentPort.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }

    private Incident saveWithTimeline(Incident incident, TimelineEventType eventType,
                                      String actor, String note, Instant now) {
        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(saved.getId(), eventType, actor, note, now));
        return saved;
    }
}
