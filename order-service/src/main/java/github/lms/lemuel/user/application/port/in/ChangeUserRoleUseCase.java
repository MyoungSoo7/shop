package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;

/**
 * 회원 역할 변경 유스케이스.
 *
 * <p><b>왜 지금 생기는가</b>: {@code User.changeRole()} 은 도메인에 있었고
 * {@code AuditAction.USER_ROLE_CHANGED} 도 enum 에 있었지만, 둘을 잇는 경로가 없었다.
 * 즉 <b>역할을 바꾸는 유일한 방법이 DB 직접 UPDATE</b> 였고, 그 조작은 어디에도 기록되지
 * 않았다. 권한 상승은 감사에서 가장 먼저 보는 항목인데 그 기록이 원천적으로 없던 셈이다.
 *
 * <p>자기 자신을 강등하는 조작은 막는다 — 마지막 관리자가 스스로를 USER 로 내리면
 * 아무도 되돌릴 수 없다.
 */
public interface ChangeUserRoleUseCase {

    /**
     * 대상 회원의 역할을 바꾼다.
     *
     * @param userId    대상 회원
     * @param newRole   바꿀 역할
     * @param reason    변경 사유(필수) — 근거 없는 권한 변경은 감사에서 설명할 수 없다
     * @param actorId   조작한 관리자. 자기 자신 강등 차단에 쓴다
     * @return 변경된 회원과 <b>변경 전 역할</b>
     */
    RoleChangeResult changeRole(Long userId, UserRole newRole, String reason, Long actorId);

    /**
     * 역할 변경 결과.
     *
     * <p><b>변경 전 역할을 함께 돌려주는 이유</b>: 결과만 남기면 "MANAGER 가 됐다"는 알아도
     * 무엇에서 올라왔는지 모르고, 그러면 권한 상승인지 강등인지조차 판단할 수 없다. 감사 상세가
     * 이 값을 읽어 기록한다.
     */
    record RoleChangeResult(User user, UserRole previousRole) {
    }
}
