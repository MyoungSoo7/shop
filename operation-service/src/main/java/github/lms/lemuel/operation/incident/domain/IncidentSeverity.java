package github.lms.lemuel.operation.incident.domain;

/**
 * 인시던트 심각도 — Alertmanager 알람의 {@code labels.severity} 에서 파생.
 *
 * <p>refire 시 심각도는 <b>상향만</b> 반영한다(WARNING→CRITICAL 승격). 하향은 무시 —
 * critical 이었던 인시던트가 warning 재발화로 조용히 강등되는 것을 막는다.
 */
public enum IncidentSeverity {
    INFO(1),
    WARNING(2),
    CRITICAL(3);

    private final int rank;

    IncidentSeverity(int rank) {
        this.rank = rank;
    }

    public boolean isHigherThan(IncidentSeverity other) {
        return this.rank > other.rank;
    }

    /**
     * Alertmanager 라벨 문자열 → severity. 알람 룰은 critical/warning 만 쓰지만,
     * 미지정·미지의 라벨은 WARNING 으로 보수적으로 처리한다.
     */
    public static IncidentSeverity fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return WARNING;
        }
        return switch (label.trim().toUpperCase()) {
            case "CRITICAL" -> CRITICAL;
            case "INFO" -> INFO;
            default -> WARNING;
        };
    }
}
