package github.lms.lemuel.operation.incident.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 인시던트 애그리게잇 루트 — 알람/이상 신호의 라이프사이클 컨테이너 (순수 POJO).
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>상태 전이는 {@link IncidentStatus#canTransitionTo} 상태머신이 강제 — 비정상 전이는
 *       {@link InvalidIncidentTransitionException} (OrderStatus 컨벤션).</li>
 *   <li>같은 (source, correlationKey) 의 활성 인시던트는 최대 1건 — DB partial unique index
 *       (uq_incident_active) 가 최종 방어선이고, 도메인은 refire 로 병합을 표현한다.</li>
 *   <li>refire 시 심각도는 상향만 반영, occurrenceCount/lastSeenAt 갱신.
 *       REFIRED 타임라인은 억제 간격(기본 30분) 경과 시에만 기록해 repeat_interval 폭주를 막는다.</li>
 * </ul>
 */
public class Incident {

    /** 자동 해제(Alertmanager resolved 수신)의 actor 표기. */
    public static final String AUTO_ACTOR = "alertmanager";

    /** 이상 탐지(Phase 3) 자동 생성/해제의 actor 표기. */
    public static final String ANOMALY_ACTOR = "anomaly-detector";

    private final Long id;
    private final String correlationKey;
    private final IncidentSource source;
    private final SignalCategory category;
    private IncidentSeverity severity;
    private IncidentStatus status;
    private final String title;
    private final String description;
    private final String service;
    private final Map<String, String> labels;
    private final Map<String, String> annotations;
    private final Instant firstSeenAt;
    private Instant lastSeenAt;
    private int occurrenceCount;
    private Instant lastRefireLoggedAt;
    private Instant acknowledgedAt;
    private String acknowledgedBy;
    private Instant resolvedAt;
    private String resolvedBy;
    private final long version;

    private Incident(Builder b) {
        this.id = b.id;
        this.correlationKey = Objects.requireNonNull(b.correlationKey, "correlationKey");
        this.source = Objects.requireNonNull(b.source, "source");
        this.category = Objects.requireNonNull(b.category, "category");
        this.severity = Objects.requireNonNull(b.severity, "severity");
        this.status = Objects.requireNonNull(b.status, "status");
        this.title = Objects.requireNonNull(b.title, "title");
        this.description = b.description;
        this.service = b.service;
        this.labels = b.labels == null ? Map.of() : Map.copyOf(b.labels);
        this.annotations = b.annotations == null ? Map.of() : Map.copyOf(b.annotations);
        this.firstSeenAt = Objects.requireNonNull(b.firstSeenAt, "firstSeenAt");
        this.lastSeenAt = Objects.requireNonNull(b.lastSeenAt, "lastSeenAt");
        this.occurrenceCount = b.occurrenceCount;
        this.lastRefireLoggedAt = b.lastRefireLoggedAt;
        this.acknowledgedAt = b.acknowledgedAt;
        this.acknowledgedBy = b.acknowledgedBy;
        this.resolvedAt = b.resolvedAt;
        this.resolvedBy = b.resolvedBy;
        this.version = b.version;
    }

    /** Alertmanager firing alert 로부터 새 인시던트를 연다. */
    public static Incident openFromAlert(String fingerprint, SignalCategory category, IncidentSeverity severity,
                                         String title, String description, String service,
                                         Map<String, String> labels, Map<String, String> annotations,
                                         Instant startsAt, Instant now) {
        return builder()
                .correlationKey(fingerprint)
                .source(IncidentSource.ALERTMANAGER)
                .category(category)
                .severity(severity)
                .status(IncidentStatus.OPEN)
                .title(title)
                .description(description)
                .service(service)
                .labels(labels)
                .annotations(annotations)
                .firstSeenAt(startsAt != null ? startsAt : now)
                .lastSeenAt(now)
                .occurrenceCount(1)
                .build();
    }

    /**
     * 베이스라인 이상 탐지(Phase 3)로부터 새 인시던트를 연다.
     *
     * <p>correlationKey 는 metric_key(예 "settlement") — (source=ANOMALY, correlationKey) 조합이
     * uq_incident_active 로 metric 당 활성 1건을 보장한다. Alertmanager 경로(fingerprint)와
     * 키 공간이 분리되어 채널 간 충돌은 없다.
     */
    public static Incident openFromAnomaly(String metricKey, SignalCategory category, IncidentSeverity severity,
                                           String title, String description, Instant now) {
        return builder()
                .correlationKey(metricKey)
                .source(IncidentSource.ANOMALY)
                .category(category)
                .severity(severity)
                .status(IncidentStatus.OPEN)
                .title(title)
                .description(description)
                .service(metricKey)
                .labels(Map.of())
                .annotations(Map.of())
                .firstSeenAt(now)
                .lastSeenAt(now)
                .occurrenceCount(1)
                .build();
    }

    /**
     * 활성 인시던트에 대한 firing 재수신 반영.
     *
     * @return 타임라인 기록 필요 여부 + 심각도 승격 여부 — 서비스가 REFIRED 행 기록을 결정하는 재료
     */
    public RefireResult refire(IncidentSeverity incoming, Instant now, Duration timelineSuppression) {
        requireActive();
        this.lastSeenAt = now;
        this.occurrenceCount++;

        boolean upgraded = false;
        if (incoming != null && incoming.isHigherThan(this.severity)) {
            this.severity = incoming;
            upgraded = true;
        }
        // 승격은 억제 없이 즉시 기록, 일반 refire 는 직전 기록 후 억제 간격 경과 시에만
        boolean logTimeline = upgraded
                || lastRefireLoggedAt == null
                || !now.isBefore(lastRefireLoggedAt.plus(timelineSuppression));
        if (logTimeline) {
            this.lastRefireLoggedAt = now;
        }
        return new RefireResult(logTimeline, upgraded);
    }

    public record RefireResult(boolean timelineLogged, boolean severityUpgraded) {
    }

    /** 운영자 확인 처리. */
    public void acknowledge(String actor, Instant now) {
        transitionTo(IncidentStatus.ACKNOWLEDGED);
        this.acknowledgedAt = now;
        this.acknowledgedBy = actor;
    }

    /** 운영자 수동 해제. */
    public void resolve(String actor, Instant now) {
        transitionTo(IncidentStatus.RESOLVED);
        this.resolvedAt = now;
        this.resolvedBy = actor;
    }

    /** Alertmanager resolved 수신에 의한 자동 해제 — OPEN/ACKNOWLEDGED 어느 쪽에서도 허용. */
    public void autoResolve(Instant resolvedAt) {
        autoResolve(AUTO_ACTOR, resolvedAt);
    }

    /**
     * 시스템에 의한 자동 해제 — actor 를 명시한다(Alertmanager={@link #AUTO_ACTOR},
     * 이상 탐지 정상복귀={@link #ANOMALY_ACTOR}). OPEN/ACKNOWLEDGED 어느 쪽에서도 허용.
     */
    public void autoResolve(String actor, Instant resolvedAt) {
        transitionTo(IncidentStatus.RESOLVED);
        this.resolvedAt = resolvedAt;
        this.resolvedBy = actor;
    }

    /** 오탐 처리 — 재발 통계에서 제외할 수 있도록 RESOLVED 와 구분해 보존. */
    public void markFalsePositive(String actor, Instant now) {
        transitionTo(IncidentStatus.FALSE_POSITIVE);
        this.resolvedAt = now;
        this.resolvedBy = actor;
    }

    private void transitionTo(IncidentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidIncidentTransitionException(status, target);
        }
        this.status = target;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new InvalidIncidentTransitionException(status, status);
        }
    }

    public boolean isActive() {
        return status.isActive();
    }

    public Long getId() {
        return id;
    }

    public String getCorrelationKey() {
        return correlationKey;
    }

    public IncidentSource getSource() {
        return source;
    }

    public SignalCategory getCategory() {
        return category;
    }

    public IncidentSeverity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getService() {
        return service;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getLastRefireLoggedAt() {
        return lastRefireLoggedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public long getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 영속성 어댑터의 재구성(reconstitution)과 팩토리가 공용하는 빌더. */
    public static class Builder {
        private Long id;
        private String correlationKey;
        private IncidentSource source;
        private SignalCategory category;
        private IncidentSeverity severity;
        private IncidentStatus status;
        private String title;
        private String description;
        private String service;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private Instant firstSeenAt;
        private Instant lastSeenAt;
        private int occurrenceCount = 1;
        private Instant lastRefireLoggedAt;
        private Instant acknowledgedAt;
        private String acknowledgedBy;
        private Instant resolvedAt;
        private String resolvedBy;
        private long version;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder correlationKey(String v) {
            this.correlationKey = v;
            return this;
        }

        public Builder source(IncidentSource v) {
            this.source = v;
            return this;
        }

        public Builder category(SignalCategory v) {
            this.category = v;
            return this;
        }

        public Builder severity(IncidentSeverity v) {
            this.severity = v;
            return this;
        }

        public Builder status(IncidentStatus v) {
            this.status = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder service(String v) {
            this.service = v;
            return this;
        }

        public Builder labels(Map<String, String> v) {
            this.labels = v;
            return this;
        }

        public Builder annotations(Map<String, String> v) {
            this.annotations = v;
            return this;
        }

        public Builder firstSeenAt(Instant v) {
            this.firstSeenAt = v;
            return this;
        }

        public Builder lastSeenAt(Instant v) {
            this.lastSeenAt = v;
            return this;
        }

        public Builder occurrenceCount(int v) {
            this.occurrenceCount = v;
            return this;
        }

        public Builder lastRefireLoggedAt(Instant v) {
            this.lastRefireLoggedAt = v;
            return this;
        }

        public Builder acknowledgedAt(Instant v) {
            this.acknowledgedAt = v;
            return this;
        }

        public Builder acknowledgedBy(String v) {
            this.acknowledgedBy = v;
            return this;
        }

        public Builder resolvedAt(Instant v) {
            this.resolvedAt = v;
            return this;
        }

        public Builder resolvedBy(String v) {
            this.resolvedBy = v;
            return this;
        }

        public Builder version(long v) {
            this.version = v;
            return this;
        }

        public Incident build() {
            return new Incident(this);
        }
    }
}
