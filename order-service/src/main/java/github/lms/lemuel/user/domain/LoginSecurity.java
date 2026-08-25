package github.lms.lemuel.user.domain;

import java.time.LocalDateTime;

/**
 * 계정 하나의 로그인 보안 상태 — 연속 실패 횟수 · 잠금 만료 시각 · 마지막 비밀번호 변경 시각 ·
 * 마지막 로그인 성공 시각.
 *
 * <p><b>불변 값 객체</b>다. 상태가 바뀌는 사건(실패/성공/비밀번호 변경/잠금 해제)마다 새 인스턴스를
 * 돌려주고, {@link User} 가 자기 필드를 교체한다. 필드를 User 에 흩어 두면 "실패를 세는 곳"과
 * "잠금을 푸는 곳"이 갈라져 한쪽만 고치는 버그가 생기는데, 여기서는 전이가 한 파일 안에 모여 있다.
 *
 * <p>시각은 전부 인자로 받는다({@code now}) — 도메인이 {@code LocalDateTime.now()} 를 직접 부르면
 * 잠금 만료·비밀번호 만료 같은 시간 규칙을 테스트에서 재현할 수 없다.
 */
public final class LoginSecurity {

    private final int failedAttempts;
    private final LocalDateTime lockedUntil;
    private final LocalDateTime passwordChangedAt;
    private final LocalDateTime lastLoginAt;

    private LoginSecurity(int failedAttempts, LocalDateTime lockedUntil,
                          LocalDateTime passwordChangedAt, LocalDateTime lastLoginAt) {
        this.failedAttempts = Math.max(failedAttempts, 0);
        this.lockedUntil = lockedUntil;
        this.passwordChangedAt = passwordChangedAt;
        this.lastLoginAt = lastLoginAt;
    }

    /** 새 계정 — 실패 0, 잠금 없음, 비밀번호는 방금 정했고, 아직 한 번도 로그인하지 않았다. */
    public static LoginSecurity initial(LocalDateTime passwordChangedAt) {
        return new LoginSecurity(0, null, passwordChangedAt, null);
    }

    /**
     * 영속 레코드 복원 전용 — 마지막 로그인 시각을 모르는 레코드.
     *
     * <p>{@code last_login_at} 도입 이전에 저장된 계정이 여기 해당한다. <b>"모른다"를 null 로
     * 그대로 남긴다</b> — 복원 시각이나 가입 시각으로 채우면 한 번도 로그인한 적 없는 계정이
     * "방금 쓴 계정"으로 보여, 미사용 관리자 계정 정리라는 이 값의 유일한 용도가 무너진다.
     */
    public static LoginSecurity restore(int failedAttempts, LocalDateTime lockedUntil,
                                        LocalDateTime passwordChangedAt) {
        return restore(failedAttempts, lockedUntil, passwordChangedAt, null);
    }

    /** 영속 레코드 복원 전용. */
    public static LoginSecurity restore(int failedAttempts, LocalDateTime lockedUntil,
                                        LocalDateTime passwordChangedAt, LocalDateTime lastLoginAt) {
        return new LoginSecurity(failedAttempts, lockedUntil, passwordChangedAt, lastLoginAt);
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
        return new LoginSecurity(attempts, until, passwordChangedAt, lastLoginAt);
    }

    /**
     * 로그인 성공 — 카운터와 잠금을 모두 지우고 <b>마지막 로그인 시각을 찍는다</b>.
     * 성공 한 번이 과거 실패를 무효로 만든다.
     *
     * <p>이 시각을 남기는 유일한 자리다. 감사 로그({@code LOGIN_SUCCESS})에도 로그인은 남지만
     * 그건 <b>사건의 나열</b>이라 "이 계정이 마지막으로 쓰인 게 언제인가"를 물으려면 계정마다
     * 로그 전체를 훑어야 하고, 감사 로그는 보존 기간이 지나면 사라진다. 계정 옆에 붙은 값
     * 하나여야 미사용 관리자 계정을 한 번의 조회로 찾을 수 있다.
     */
    public LoginSecurity afterSuccess(LocalDateTime now) {
        return new LoginSecurity(0, null, passwordChangedAt, now);
    }

    /**
     * 비밀번호 변경 — 사용 기한을 새로 시작하고 <b>잠금도 함께 푼다</b>.
     * 비밀번호 재설정은 정당한 소유자가 통제권을 되찾는 경로이므로, 여기서 잠금을 남기면
     * 재설정을 마친 사용자가 여전히 들어오지 못한다.
     *
     * <p>마지막 로그인 시각은 <b>건드리지 않는다</b> — 비밀번호를 바꾼 것은 로그인이 아니다.
     */
    public LoginSecurity afterPasswordChange(LocalDateTime now) {
        return new LoginSecurity(0, null, now, lastLoginAt);
    }

    /**
     * 운영자에 의한 잠금 해제 — 잠금과 <b>실패 카운터를 함께</b> 지운다.
     *
     * <p>카운터를 남긴 채 잠금만 풀면 임계에 이미 도달해 있으므로 다음 실패 한 번에 즉시 다시
     * 잠긴다. 그러면 운영자는 풀어 줬다고 믿고 사용자는 여전히 못 들어와, 양쪽 다 원인을 모른다.
     *
     * <p>비밀번호 기준 시각과 마지막 로그인 시각은 그대로 둔다 — 잠금 해제는 자격 증명을 바꾸는
     * 조작도, 로그인도 아니다.
     */
    public LoginSecurity afterUnlock() {
        return new LoginSecurity(0, null, passwordChangedAt, lastLoginAt);
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

    /** 마지막 로그인 성공 시각. 한 번도 로그인하지 않았거나 기록 이전 계정이면 {@code null}. */
    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }
}
