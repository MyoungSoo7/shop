package github.lms.lemuel.organization.application.exception;

/** 멤버십을 찾을 수 없음(초대 없음/이미 제거 등) — 웹 어댑터에서 404 로 매핑된다. */
public class MembershipNotFoundException extends RuntimeException {

    public MembershipNotFoundException(Long organizationId, Long userId) {
        super("멤버십을 찾을 수 없습니다: org=%d user=%d".formatted(organizationId, userId));
    }
}
