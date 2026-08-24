package github.lms.lemuel.operation.anomaly.application.service;

import github.lms.lemuel.operation.anomaly.domain.AnomalyDecision;
import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.application.port.out.LoadIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.RecordTimelinePort;
import github.lms.lemuel.operation.incident.application.port.out.SaveIncidentPort;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import github.lms.lemuel.operation.incident.domain.TimelineEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * metric 1건의 이상 판정을 인시던트 라이프사이클에 반영하는 트랜잭션 경계 — {@link AnomalyDetectionService} 위임 대상.
 *
 * <p>{@code AlertApplier} 와 같은 이유로 별도 빈: uq_incident_active 위반/낙관적 락 충돌 시 트랜잭션이
 * rollback-only 로 오염되므로 metric 1건 = 1 트랜잭션 경계를 만들고, 재시도는 새 트랜잭션(새 apply 호출)으로
 * 수행한다(자기호출로는 @Transactional 프록시 미적용). Alertmanager 경로와 key 공간(source=ANOMALY,
 * correlation=metric_key)이 분리돼 채널 간 충돌은 없지만, webhook 과 동일한 견고성을 유지한다.
 *
 * <p>인시던트 생성/refire/자동해제는 모두 incident BC 의 도메인·포트를 재사용한다 — Phase 3 는
 * 새 라이프사이클을 만들지 않고 {@code source=ANOMALY} 로 같은 테이블·상태머신에 얹는다.
 */
@Service
public class AnomalyIncidentApplier {

    private static final Logger log = LoggerFactory.getLogger(AnomalyIncidentApplier.class);

    /** 1회 반영의 결과 — 상위 집계용. */
    public enum Outcome {
        OPENED, REFIRED, AUTO_RESOLVED, NONE
    }

    private final LoadIncidentPort loadIncidentPort;
    private final SaveIncidentPort saveIncidentPort;
    private final RecordTimelinePort recordTimelinePort;
    private final OpsProperties properties;

    public AnomalyIncidentApplier(LoadIncidentPort loadIncidentPort, SaveIncidentPort saveIncidentPort,
                                  RecordTimelinePort recordTimelinePort, OpsProperties properties) {
        this.loadIncidentPort = loadIncidentPort;
        this.saveIncidentPort = saveIncidentPort;
        this.recordTimelinePort = recordTimelinePort;
        this.properties = properties;
    }

    @Transactional
    public Outcome apply(String metricKey, SignalCategory category, AnomalyDecision decision,
                         boolean resolveEligible, Instant now) {
        Optional<Incident> active = loadIncidentPort.findActive(IncidentSource.ANOMALY, metricKey);

        if (decision.isAnomaly()) {
            IncidentSeverity severity = decision.critical() ? IncidentSeverity.CRITICAL : IncidentSeverity.WARNING;
            if (active.isEmpty()) {
                return openNew(metricKey, category, severity, decision, now);
            }
            return refire(active.get(), severity, decision, now);
        }

        // 정상: 활성 인시던트가 있고 정상 복귀가 K회 연속 지속됐으면 자동 해제
        if (active.isPresent() && resolveEligible) {
            return autoResolve(active.get(), now);
        }
        return Outcome.NONE;
    }

    private Outcome openNew(String metricKey, SignalCategory category, IncidentSeverity severity,
                            AnomalyDecision decision, Instant now) {
        Incident incident = Incident.openFromAnomaly(
                metricKey, category, severity, "실패율 이상 급증: " + metricKey, decision.reason(), now);
        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(
                saved.getId(), TimelineEventType.OPENED, Incident.ANOMALY_ACTOR, decision.reason(), now));
        log.info("이상 인시던트 OPEN: id={} metric={} severity={} z={}",
                saved.getId(), metricKey, severity, "%.2f".formatted(decision.zScore()));
        return Outcome.OPENED;
    }

    private Outcome refire(Incident incident, IncidentSeverity severity, AnomalyDecision decision, Instant now) {
        IncidentSeverity before = incident.getSeverity();
        Incident.RefireResult result = incident.refire(severity, now, properties.getRefireTimelineSuppression());
        Incident saved = saveIncidentPort.save(incident);
        if (result.timelineLogged()) {
            String note = result.severityUpgraded()
                    ? "severity 승격: %s → %s (z=%.2f)".formatted(before, saved.getSeverity(), decision.zScore())
                    : "이상 지속 누적 %d회 (z=%.2f)".formatted(saved.getOccurrenceCount(), decision.zScore());
            recordTimelinePort.record(IncidentTimelineEntry.of(
                    saved.getId(), TimelineEventType.REFIRED, Incident.ANOMALY_ACTOR, note, now));
        }
        return Outcome.REFIRED;
    }

    private Outcome autoResolve(Incident incident, Instant now) {
        incident.autoResolve(Incident.ANOMALY_ACTOR, now);
        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(
                saved.getId(), TimelineEventType.AUTO_RESOLVED, Incident.ANOMALY_ACTOR,
                "정상 복귀 지속 — 자동 해제", now));
        log.info("이상 인시던트 자동 해제: id={} metric={}", saved.getId(), saved.getCorrelationKey());
        return Outcome.AUTO_RESOLVED;
    }
}
