package github.lms.lemuel.operation.incident.domain;

/**
 * 인시던트 발원 채널.
 *
 * <ul>
 *   <li>{@link #ALERTMANAGER} — Prometheus 알람 webhook (Phase 1)</li>
 *   <li>{@link #ANOMALY} — 자체 베이스라인 이상 탐지 (Phase 3 선점)</li>
 *   <li>{@link #MANUAL} — 운영자 수동 등록 (향후)</li>
 * </ul>
 *
 * <p>활성 인시던트 유일성(uq_incident_active)은 (source, correlation_key) 조합 —
 * 같은 신호라도 채널이 다르면 별건으로 적재되고, 채널 간 상관(correlation) 병합은
 * Phase 3 탐지 도입 시 다룬다.
 */
public enum IncidentSource {
    ALERTMANAGER,
    ANOMALY,
    MANUAL
}
