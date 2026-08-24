package github.lms.lemuel.organization.application.exception;

/** 이미 활성 슬롯(INVITED/ACTIVE)을 점유한 멤버 재초대 — 웹 어댑터에서 409 로 매핑된다. */
public class DuplicateMembershipException extends RuntimeException {

    public DuplicateMembershipException(Long organizationId, Long userId) {
        super("이미 활성 멤버십이 있습니다: org=%d user=%d".formatted(organizationId, userId));
    }
}
