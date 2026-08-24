package github.lms.lemuel.organization.domain;

/** 조직 상태머신이 허용하지 않는 전이 시도 — 웹 어댑터에서 409 로 매핑된다. */
public class InvalidOrganizationTransitionException extends RuntimeException {

    public InvalidOrganizationTransitionException(OrganizationStatus from, OrganizationStatus to) {
        super("허용되지 않는 조직 상태 전이: %s → %s".formatted(from, to));
    }
}
