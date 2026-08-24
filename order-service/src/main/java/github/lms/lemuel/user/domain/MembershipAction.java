package github.lms.lemuel.user.domain;

/**
 * 회원 승인 처리 액션 (감사 이력용).
 *
 * membership_approvals.action 컬럼과 매핑된다.
 */
public enum MembershipAction {
    APPROVE,    // 승인 (PENDING → APPROVED)
    REJECT,     // 반려 (PENDING → REJECTED)
    SUSPEND,    // 정지 (APPROVED → SUSPENDED)
    REINSTATE   // 정지 해제 (SUSPENDED → APPROVED)
}
