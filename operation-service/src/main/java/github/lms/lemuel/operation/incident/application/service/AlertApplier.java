package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.AlertCommand;
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

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * alert 1건의 트랜잭션 반영 — {@link IngestAlertService} 의 위임 대상.
 *
 * <p>별도 빈으로 분리한 이유: uq_incident_active 위반(동시 webhook 경쟁) 시 트랜잭션이
 * rollback-only 로 오염되므로, alert 1건 = 1 트랜잭션 경계를 만들고 재시도는
 * 새 트랜잭션(새 {@code apply} 호출)으로 수행해야 한다. 같은 빈의 자기호출로는
 * {@code @Transactional} 프록시가 적용되지 않는다.
 */
@Service
public class AlertApplier {

    private static final Logger log = LoggerFactory.getLogger(AlertApplier.class);

    static final String LABEL_ALERTNAME = "alertname";
    static final String LABEL_SEVERITY = "severity";
    static final String LABEL_COMPONENT = "component";
    static final String ANNOTATION_SUMMARY = "summary";
    static final String ANNOTATION_DESCRIPTION = "description";

    private final LoadIncidentPort loadIncidentPort;
    private final SaveIncidentPort saveIncidentPort;
    private final RecordTimelinePort recordTimelinePort;
    private final OpsProperties properties;
    private final Clock clock;

    public AlertApplier(LoadIncidentPort loadIncidentPort, SaveIncidentPort saveIncidentPort,
                        RecordTimelinePort recordTimelinePort, OpsProperties properties, Clock clock) {
        this.loadIncidentPort = loadIncidentPort;
        this.saveIncidentPort = saveIncidentPort;
        this.recordTimelinePort = recordTimelinePort;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void apply(AlertCommand alert) {
        Instant now = Instant.now(clock);
        Optional<Incident> active = loadIncidentPort.findActive(IncidentSource.ALERTMANAGER, alert.fingerprint());

        if (alert.firing()) {
            if (active.isEmpty()) {
                openNew(alert, now);
            } else {
                refire(active.get(), alert, now);
            }
        } else {
            // resolved: 활성 인시던트가 없으면 no-op (운영자가 이미 닫았거나 이전 유실 — 재전송이 보상)
            active.ifPresentOrElse(
                    incident -> autoResolve(incident, alert, now),
                    () -> log.debug("resolved alert 인데 활성 인시던트 없음 — no-op: fingerprint={}", alert.fingerprint()));
        }
    }

    private void openNew(AlertCommand alert, Instant now) {
        Map<String, String> labels = safe(alert.labels());
        Map<String, String> annotations = safe(alert.annotations());

        String component = labels.get(LABEL_COMPONENT);
        Incident incident = Incident.openFromAlert(
                alert.fingerprint(),
                resolveCategory(component),
                IncidentSeverity.fromLabel(labels.get(LABEL_SEVERITY)),
                labels.getOrDefault(LABEL_ALERTNAME, "unknown-alert"),
                buildDescription(annotations),
                component,
                labels,
                annotations,
                alert.startsAt(),
                now);

        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(
                saved.getId(), TimelineEventType.OPENED, Incident.AUTO_ACTOR,
                annotations.get(ANNOTATION_SUMMARY), now));
        log.info("인시던트 OPEN: id={} title={} category={} severity={}",
                saved.getId(), saved.getTitle(), saved.getCategory(), saved.getSeverity());
    }

    private void refire(Incident incident, AlertCommand alert, Instant now) {
        IncidentSeverity incoming = IncidentSeverity.fromLabel(safe(alert.labels()).get(LABEL_SEVERITY));
        IncidentSeverity before = incident.getSeverity();

        Incident.RefireResult result = incident.refire(incoming, now, properties.getRefireTimelineSuppression());
        Incident saved = saveIncidentPort.save(incident);

        if (result.timelineLogged()) {
            String note = result.severityUpgraded()
                    ? "severity 승격: %s → %s".formatted(before, saved.getSeverity())
                    : "누적 %d회 발생".formatted(saved.getOccurrenceCount());
            recordTimelinePort.record(IncidentTimelineEntry.of(
                    saved.getId(), TimelineEventType.REFIRED, Incident.AUTO_ACTOR, note, now));
        }
    }

    private void autoResolve(Incident incident, AlertCommand alert, Instant now) {
        incident.autoResolve(alert.endsAt() != null ? alert.endsAt() : now);
        Incident saved = saveIncidentPort.save(incident);
        recordTimelinePort.record(IncidentTimelineEntry.of(
                saved.getId(), TimelineEventType.AUTO_RESOLVED, Incident.AUTO_ACTOR, null, now));
        log.info("인시던트 자동 해제: id={} title={}", saved.getId(), saved.getTitle());
    }

    private SignalCategory resolveCategory(String component) {
        if (component == null || component.isBlank()) {
            log.warn("alert 에 component 라벨 없음 — {} 로 폴백", properties.getDefaultCategory());
            return fallback();
        }
        String mapped = properties.getCategoryMapping().get(component);
        if (mapped == null) {
            log.warn("category-mapping 누락: component={} — {} 로 폴백 (application.yml app.ops.category-mapping 에 추가하세요)",
                    component, properties.getDefaultCategory());
            return fallback();
        }
        try {
            return SignalCategory.valueOf(mapped);
        } catch (IllegalArgumentException e) {
            log.warn("category-mapping 값이 SignalCategory 가 아님: {} — {} 로 폴백", mapped, properties.getDefaultCategory());
            return fallback();
        }
    }

    private SignalCategory fallback() {
        try {
            return SignalCategory.valueOf(properties.getDefaultCategory());
        } catch (IllegalArgumentException e) {
            return SignalCategory.UNKNOWN;
        }
    }

    private String buildDescription(Map<String, String> annotations) {
        String summary = annotations.get(ANNOTATION_SUMMARY);
        String description = annotations.get(ANNOTATION_DESCRIPTION);
        if (summary == null) {
            return description;
        }
        if (description == null) {
            return summary;
        }
        return summary + "\n" + description;
    }

    private Map<String, String> safe(Map<String, String> map) {
        return map == null ? Map.of() : map;
    }
}
