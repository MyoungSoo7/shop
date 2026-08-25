package github.lms.lemuel.user.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.user.application.port.in.UnlockAccountUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 계정 잠금 해제 서비스.
 *
 * <p>규약은 {@link ChangeUserRoleService} 와 같다 — <b>사유 필수 · 자기 자신 금지 · 감사 상세에
 * 변경 전 상태</b>.
 *
 * <p><b>사유를 필수로 받는 이유</b>: 잠금은 무차별 대입을 막으려고 걸린다. 그것을 사람이 푸는
 * 조작은 공격 대응 절차를 한 단계 되돌리는 일이라 사후에 반드시 설명돼야 한다. "누가 언제
 * 무엇을"은 감사 로그가 남기지만 "왜"는 조작자만 안다.
 *
 * <p><b>자기 자신은 풀지 못한다</b>: 스스로 푸는 길이 열려 있으면 잠금은 그 계정에 대해
 * 존재하지 않는 것과 같다. 관리자 계정을 탈취한 쪽이 가장 먼저 하는 일이 자기 잠금 해제다.
 * 이 규칙 덕분에 잠금 해제에는 항상 두 사람이 관여한 기록이 남는다.
 *
 * <p><b>잠기지 않은 계정에도 성공으로 응답한다</b>: 잠금은 시각 기반이라 운영자가 목록에서
 * 잠긴 걸 보고 누르는 사이 저절로 만료될 수 있다. 그때 400 을 던지면 운영자는 자기 조작이
 * 실패했다고 읽고 다시 누른다. 끝 상태는 어느 쪽이든 "풀린 계정"으로 같으므로 멱등하게 두되,
 * 실제로 잠겨 있었는지는 {@code wasLocked} 로 감사에 남긴다 — 조사할 사건과 헛클릭은 구분돼야
 * 한다.
 */
@Service
public class UnlockAccountService implements UnlockAccountUseCase {

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final Clock clock;

    public UnlockAccountService(LoadUserPort loadUserPort, SaveUserPort saveUserPort, Clock clock) {
        this.loadUserPort = loadUserPort;
        this.saveUserPort = saveUserPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.ACCOUNT_UNLOCKED,
            resourceType = "User",
            resourceId = "#p0.toString()",
            detail = "{'reason': #p1,"
                    + " 'wasLocked': #result == null ? null : #result.wasLocked(),"
                    + " 'previousLockedUntil': #result == null ? null : #result.previousLockedUntil(),"
                    + " 'previousFailedAttempts': #result == null ? null : #result.previousFailedAttempts()}"
    )
    public UnlockResult unlock(Long userId, String reason, Long actorId) {
        if (reason == null || reason.isBlank()) {
            throw new UserInvariantViolationException("잠금 해제 사유는 필수입니다");
        }
        if (actorId != null && actorId.equals(userId)) {
            throw new UserInvariantViolationException(
                    "자기 자신의 잠금은 풀 수 없습니다 — 스스로 풀 수 있으면 그 계정에 잠금은 없는 것과 같습니다");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User user = loadUserPort.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        boolean wasLocked = user.isLocked(now);
        LocalDateTime previousLockedUntil = user.lockedUntil();
        int previousFailedAttempts = user.failedLoginAttempts();

        user.unlock(now);

        return new UnlockResult(saveUserPort.save(user), wasLocked, previousLockedUntil, previousFailedAttempts);
    }
}
