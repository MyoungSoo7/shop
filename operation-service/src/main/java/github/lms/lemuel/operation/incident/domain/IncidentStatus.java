package github.lms.lemuel.operation.incident.domain;

/**
 * 인시던트 라이프사이클 상태머신.
 *
 * <pre>
 * OPEN ──────────→ ACKNOWLEDGED ──→ RESOLVED
 *   │                   │
 *   ├──→ RESOLVED       └──→ FALSE_POSITIVE
 *   └──→ FALSE_POSITIVE
 * </pre>
 *
 * <p>RESOLVED / FALSE_POSITIVE 는 터미널 — 재전이 불가. 해제된 알람이 재발화하면
 * reopen 이 아니라 <b>새 인시던트</b>가 생성된다(uq_incident_active partial unique index 가
 * 활성 상태에만 걸려 있어 자연스럽게 허용). 전이 규칙은 {@link Incident} 가
 * {@code canTransitionTo} 로 강제한다 (OrderStatus 와 동일 컨벤션).
 */
public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED,
    FALSE_POSITIVE;

    public boolean canTransitionTo(IncidentStatus target) {
        return switch (this) {
            case OPEN -> target == ACKNOWLEDGED || target == RESOLVED || target == FALSE_POSITIVE;
            case ACKNOWLEDGED -> target == RESOLVED || target == FALSE_POSITIVE;
            case RESOLVED, FALSE_POSITIVE -> false;
        };
    }

    /** 활성(= 아직 닫히지 않은) 상태 여부 — uq_incident_active 인덱스의 상태 집합과 일치해야 한다. */
    public boolean isActive() {
        return this == OPEN || this == ACKNOWLEDGED;
    }
}
