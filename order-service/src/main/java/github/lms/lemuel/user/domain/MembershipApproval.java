package github.lms.lemuel.user.domain;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 회원 승인/반려/정지 처리 이력 (감사 추적용 순수 POJO).
 *
 * 누가(processedBy) 언제(createdAt) 어떤 회원(userId)을 어떤 액션(action)으로,
 * 왜(reason) 처리했는지 보존한다. DB: opslab.membership_approvals (V20260610090000)
 */
@Getter
public class MembershipApproval {

    private Long id;
    private final Long userId;
    private final MembershipAction action;
    private final String reason;
    private final Long processedBy;
    private final LocalDateTime createdAt;

    public MembershipApproval(Long userId, MembershipAction action, String reason, Long processedBy) {
        if (userId == null) {
            throw new UserInvariantViolationException("userId is required");
        }
        if (action == null) {
            throw new UserInvariantViolationException("action is required");
        }
        if (processedBy == null) {
            throw new UserInvariantViolationException("processedBy is required");
        }
        this.userId = userId;
        this.action = action;
        this.reason = reason;
        this.processedBy = processedBy;
        this.createdAt = LocalDateTime.now();
    }

    /** 영속화 후 부여된 식별자 주입 */
    public void assignId(Long id) {
        this.id = id;
    }
}
