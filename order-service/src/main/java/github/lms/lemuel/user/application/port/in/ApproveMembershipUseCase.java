package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;

/**
 * 회원 승인 워크플로 UseCase.
 *
 * 모든 처리는 관리자(processedBy)에 의해 수행되며 감사 이력이 기록된다.
 * 상태 전이 가드는 도메인({@link User})에서 강제한다.
 */
public interface ApproveMembershipUseCase {

    /** 승인: PENDING → APPROVED */
    User approve(Long userId, Long processedBy);

    /** 반려: PENDING → REJECTED */
    User reject(Long userId, String reason, Long processedBy);

    /** 정지: APPROVED → SUSPENDED */
    User suspend(Long userId, String reason, Long processedBy);

    /** 정지 해제: SUSPENDED → APPROVED */
    User reinstate(Long userId, Long processedBy);
}
