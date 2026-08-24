package github.lms.lemuel.organization.domain;

/**
 * 조직 라이프사이클 상태머신.
 *
 * <pre>
 * ACTIVE ⇄ SUSPENDED
 * </pre>
 *
 * 전이 규칙은 {@link Organization} 이 {@code canTransitionTo} 로 강제한다(기존 상태머신 컨벤션).
 */
public enum OrganizationStatus {
    ACTIVE,
    SUSPENDED;

    public boolean canTransitionTo(OrganizationStatus target) {
        return switch (this) {
            case ACTIVE -> target == SUSPENDED;
            case SUSPENDED -> target == ACTIVE;
        };
    }
}
