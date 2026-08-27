package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.application.port.in.RaiseAnomalyIncidentUseCase;
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
 * 이상 탐지 판정 1건의 인시던트 반영 — {@link RaiseAnomalyIncidentUseCase} 구현이자 트랜잭션 경계.
 *
 * <p>{@code AlertApplier} 와 같은 이유로 별도 빈: uq_incident_active 위반/낙관적 락 충돌 시 트랜잭션이
 * rollback-only 로 오염되므로 판정 1건 = 1 트랜잭션 경계를 만들고, 재시도는 새 트랜잭션(새 apply 호출)으로
 * 수행한다(자기호출로는 @Transactional 프록시 미적용). Alertmanager 경로와 key 공간(source=ANOMALY,
 * correlation=metric_key)이 분리돼 채널 간 충돌은 없지만, webhook 과 동일한 견고성을 유지한다.
 *
 * <p>인시던트 생성/refire/자동해제는 모두 incident 의 도메인·포트를 재사용한다 — 이상 탐지는
 * 새 라이프사이클을 만들지 않고 {@code source=ANOMALY} 로 같은 테이블·상태머신에 얹는다.
 *
 * <p>원래 {@code anomaly} 패키지에 있던 클래스다. 다루는 것이 전부 incident 의 것이었으므로
 * 소유 기능으로 옮기고, anomaly 에는 인바운드 포트만 남겼다.
 */
@Service
public class AnomalyIncidentService implements RaiseAnomalyIncidentUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnomalyIncidentService.class);

    private final LoadIncidentPort loadIncidentPort;
    private final SaveIncidentPort saveIncidentPort;
    private final RecordTimelinePort recordTimelinePort;
    private final OpsProperties properties;

    public AnomalyIncidentService(LoadIncidentPort loadIncidentPort, SaveIncidentPort saveIncidentPort,
                                  RecordTimelinePort recordTimelinePort, OpsProperties properties) {
        this.loadIncidentPort = loadIncidentPort;
        this.saveIncidentPort = saveIncidentPort;
        this.recordTimelinePort = recordTimelinePort;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Result apply(Command command) {
        String metricKey = command.metricKey();
        Instant now = command.observedAt();
        Optional<Incident> active = loadIncidentPort.findActive(IncidentSource.ANOMALY, metricKey);

        if (command.anomaly()) {
            IncidentSeverity severity = command.critical() ? IncidentSeverity.CRITICAL : IncidentSeverity.WARNING;
            if (active.isEmpty()) {
                return openNew(command, severity, now);
            }
            return refire(active.get(), command, severity, now);
        }

        // 정상: 활성 인시던트가 있고 정상 복귀가 K회 연속 지속됐으면 자동 해제
        if (active.isPresent() && command.resolveEligible()) {
            return autoResolve(active.get(), now);
        }
        return Result.NONE;
    }

    private Result openNew(Command command, IncidentSeverity severity, Instant now) {
        String metricKey = command.metricKey();
        Incident incident = Incident.openFromAnomaly(
                metricKey, resolveCategory(command), severity,
                "실패율 이상 급증: " + metricKey, command.reason(), now);
        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(
                saved.getId(), TimelineEventType.OPENED, Incident.ANOMALY_ACTOR, command.reason(), now));
        log.info("이상 인시던트 OPEN: id={} metric={} severity={} z={}",
                saved.getId(), metricKey, severity, "%.2f".formatted(command.zScore()));
        return Result.OPENED;
    }

    private Result refire(Incident incident, Command command, IncidentSeverity severity, Instant now) {
        IncidentSeverity before = incident.getSeverity();
        Incident.RefireResult result = incident.refire(severity, now, properties.getRefireTimelineSuppression());
        Incident saved = saveIncidentPort.save(incident);
        if (result.timelineLogged()) {
            String note = result.severityUpgraded()
                    ? "severity 승격: %s → %s (z=%.2f)".formatted(before, saved.getSeverity(), command.zScore())
                    : "이상 지속 누적 %d회 (z=%.2f)".formatted(saved.getOccurrenceCount(), command.zScore());
            recordTimelinePort.record(IncidentTimelineEntry.of(
                    saved.getId(), TimelineEventType.REFIRED, Incident.ANOMALY_ACTOR, note, now));
        }
        return Result.REFIRED;
    }

    private Result autoResolve(Incident incident, Instant now) {
        incident.autoResolve(Incident.ANOMALY_ACTOR, now);
        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(
                saved.getId(), TimelineEventType.AUTO_RESOLVED, Incident.ANOMALY_ACTOR,
                "정상 복귀 지속 — 자동 해제", now));
        log.info("이상 인시던트 자동 해제: id={} metric={}", saved.getId(), saved.getCorrelationKey());
        return Result.AUTO_RESOLVED;
    }

    /**
     * 설정 문자열을 신호 분류로 옮긴다. 알 수 없는 값이면 UNKNOWN 으로 떨어뜨린다 —
     * 오타 하나로 탐지가 통째로 멈추는 것보다 분류가 비는 편이 낫다.
     *
     * <p>이 변환은 원래 호출자(anomaly) 에 있었다. {@code SignalCategory} 가 incident 의 도메인이므로
     * 소유자 쪽에서 해석하는 것이 맞다.
     */
    private SignalCategory resolveCategory(Command command) {
        try {
            return SignalCategory.valueOf(command.categoryName());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("알 수 없는 신호 분류 — UNKNOWN 폴백: metric={} value={}",
                    command.metricKey(), command.categoryName());
            return SignalCategory.UNKNOWN;
        }
    }
}
