package github.lms.lemuel.organization.application.exception;

/** 조직을 찾을 수 없음 — 웹 어댑터에서 404 로 매핑된다. */
public class OrganizationNotFoundException extends RuntimeException {

    public OrganizationNotFoundException(Long organizationId) {
        super("조직을 찾을 수 없습니다: " + organizationId);
    }
}
