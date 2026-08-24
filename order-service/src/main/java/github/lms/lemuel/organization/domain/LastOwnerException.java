package github.lms.lemuel.organization.domain;

/**
 * 조직의 마지막 활성 OWNER 를 강등/제거하려는 시도 — 조직은 항상 OWNER 를 최소 1명 유지해야 한다.
 * 웹 어댑터에서 422 로 매핑된다(규칙 위반).
 */
public class LastOwnerException extends RuntimeException {

    public LastOwnerException(Long organizationId) {
        super("조직 %d 의 마지막 OWNER 는 강등/제거할 수 없습니다(최소 1명 유지)".formatted(organizationId));
    }
}
