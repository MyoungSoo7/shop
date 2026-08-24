package github.lms.lemuel.user.domain;

import java.time.LocalDateTime;

/**
 * 계정 하나의 로그인 보안 상태 — 연속 실패 횟수 · 잠금 만료 시각 · 마지막 비밀번호 변경 시각.
 *
 * <p><b>불변 값 객체</b>다. 상태가 바뀌는 사건(실패/성공/비밀번호 변경)마다 새 인스턴스를 돌려주고,
 * {@link User} 가 자기 필드를 교체한다. 필드 3 개를 User 에 흩어 두면 "실패를 세는 곳"과 "잠금을 푸는 곳"이
 * 갈라져 한쪽만 고치는 버그가 생기는데, 여기서는 전이가 한 파일 안에 모여 있다.
 *
 * <p>시각은 전부 인자로 받는다({@code now}) — 도메인이 {@code LocalDateTime.now()} 를 직접 부르면
 * 잠금 만료·비밀번호 만료 같은 시간 규칙을 테스트에서 재현할 수 없다.
 */
public final class LoginSecurity {

    private final int failedAttempts;
    private final LocalDateTime lockedUntil;
    private final LocalDateTime passwordChangedAt;

    private LoginSecurity(int failedAttempts, LocalDateTime lockedUntil, LocalDateTime passwordChangedAt) {
        this.failedAttempts = Math.max(failedAttempts, 0);
        this.lockedUntil = lockedUntil;
        this.passwordChangedAt = passwordChangedAt;
    }

    /** 새 계정 — 실패 0, 잠금 없음, 비밀번호는 방금 정했다. */
    public static LoginSecurity initial(LocalDateTime passwordChangedAt) {
        return new LoginSecurity(0, null, passwordChangedAt);
    }

    /** 영속 레코드 복원 전용. */
    public static LoginSecurity restore(int failedAttempts, LocalDateTime lockedUntil,
                                        LocalDateTime passwordChangedAt) {
        return new LoginSecurity(failedAttempts, lockedUntil, passwordChangedAt);
    }

    /**
     * 지금 잠겨 있는지. 잠금 만료 <b>정각은 이미 풀린 것</b>으로 본다(경계는 사용자에게 유리하게).
     */
    public boolean isLockedAt(LocalDateTime now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * 비밀번호 검증 실패 1 건 반영. 임계에 도달하면 잠금 만료 시각을 찍는다.
     *
     * <p>임계 도달 후에도 카운터는 계속 오른다 — 잠긴 계정에 계속 두드리는 시도는 그 자체로 신호다
     * (감사 로그에 남는 값이 5 에서 멈추면 "5 회에서 멈춘 사람"과 "500 회 두드린 사람"을 구분할 수 없다).
     */
    public LoginSecurity afterFailure(LoginSecurityPolicy policy, LocalDateTime now) {
        int attempts = failedAttempts + 1;
        LocalDateTime until = attempts >= policy.maxFailedAttempts()
                ? now.plus(policy.lockDuration())
                : lockedUntil;
        return new LoginSecurity(attempts, until, passwordChangedAt);
    }

    /** 로그인 성공 — 카운터와 잠금을 모두 지운다. 성공 한 번이 과거 실패를 무효로 만든다. */
    public LoginSecurity afterSuccess() {
        return new LoginSecurity(0, null, passwordChangedAt);
    }

    /**
     * 비밀번호 변경 — 사용 기한을 새로 시작하고 <b>잠금도 함께 푼다</b>.
     * 비밀번호 재설정은 정당한 소유자가 통제권을 되찾는 경로이므로, 여기서 잠금을 남기면
     * 재설정을 마친 사용자가 여전히 들어오지 못한다.
     */
    public LoginSecurity afterPasswordChange(LocalDateTime now) {
        return new LoginSecurity(0, null, now);
    }

    /**
     * 비밀번호 사용 기한 초과 여부.
     *
     * <p>기준 시각이 없는 계정(정책 도입 이전 데이터)은 <b>만료로 보지 않는다</b> — 모르는 것을
     * 만료로 취급하면 마이그레이션 순간 전 사용자가 로그인 불가가 된다.
     */
    public boolean passwordExpiredAt(LoginSecurityPolicy policy, LocalDateTime now) {
        if (!policy.checksPasswordAge() || passwordChangedAt == null) {
            return false;
        }
        return now.isAfter(passwordChangedAt.plus(policy.passwordMaxAge()));
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }
}
