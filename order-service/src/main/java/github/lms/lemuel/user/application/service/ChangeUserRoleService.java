package github.lms.lemuel.user.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.user.application.port.in.ChangeUserRoleUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 역할 변경 서비스.
 *
 * <p><b>사유를 필수로 받는 이유</b>: 권한 상승은 사후에 반드시 설명해야 하는 조작이다.
 * "누가 언제 무엇을"까지는 감사 로그가 자동으로 남기지만 "왜"는 조작자만 안다. 그 한 줄이
 * 없으면 6개월 뒤 감사에서 이 변경은 설명할 수 없는 사건이 된다.
 *
 * <p><b>자기 자신은 바꾸지 못한다</b>: 관리자가 스스로를 강등하면 그 순간 되돌릴 권한도 함께
 * 사라진다. 반대로 스스로를 승격하는 경로도 같이 막힌다 — 권한 상승은 남이 해 주는 것이어야
 * 두 사람이 관여한 기록이 남는다.
 *
 * <p>감사 상세에 <b>변경 전 역할</b>을 담는다. 결과만 남기면 "MANAGER 가 됐다"는 알아도
 * "무엇에서 올라왔는지"를 모르고, 그러면 권한 상승인지 강등인지조차 판단할 수 없다.
 */
@Service
@RequiredArgsConstructor
public class ChangeUserRoleService implements ChangeUserRoleUseCase {

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.USER_ROLE_CHANGED,
            resourceType = "User",
            resourceId = "#p0.toString()",
            detail = "{'newRole': #p1.name(), 'reason': #p2, 'previousRole': #result == null ? null : #result.previousRole().name()}"
    )
    public RoleChangeResult changeRole(Long userId, UserRole newRole, String reason, Long actorId) {
        if (newRole == null) {
            throw new UserInvariantViolationException("변경할 역할이 필요합니다");
        }
        if (reason == null || reason.isBlank()) {
            throw new UserInvariantViolationException("역할 변경 사유는 필수입니다");
        }
        if (actorId != null && actorId.equals(userId)) {
            throw new UserInvariantViolationException(
                    "자기 자신의 역할은 바꿀 수 없습니다 — 되돌릴 권한까지 함께 사라집니다");
        }

        User user = loadUserPort.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserRole previousRole = user.getRole();
        user.changeRole(newRole);
        return new RoleChangeResult(saveUserPort.save(user), previousRole);
    }
}
