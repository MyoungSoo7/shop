package github.lms.lemuel.organization.domain;

/** 멤버십 상태머신이 허용하지 않는 전이 시도 — 웹 어댑터에서 409 로 매핑된다. */
public class InvalidMembershipTransitionException extends RuntimeException {

    public InvalidMembershipTransitionException(MembershipStatus from, MembershipStatus to) {
        super("허용되지 않는 멤버십 상태 전이: %s → %s".formatted(from, to));
    }
}
