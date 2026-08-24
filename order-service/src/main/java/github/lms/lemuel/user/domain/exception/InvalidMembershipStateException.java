package github.lms.lemuel.user.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.user.domain.MembershipStatus;

/**
 * 멤버십 상태머신 위반 — 현재 상태가 요구 상태와 달라 허용되지 않은 멤버십 전이를 시도했다.
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 현재/요구 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidMembershipStateException extends BusinessException {

    private final transient MembershipStatus from;
    private final transient MembershipStatus to;

    public InvalidMembershipStateException(MembershipStatus from, MembershipStatus to) {
        super(ErrorCode.INVALID_STATE,
                "Invalid membership transition: expected " + to + " but was " + from);
        this.from = from;
        this.to = to;
    }

    public MembershipStatus getFrom() {
        return from;
    }

    public MembershipStatus getTo() {
        return to;
    }
}
