package github.lms.lemuel.user.domain;

import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import java.time.Duration;

/**
 * 로그인 보안 정책 — 무차별 대입 잠금과 비밀번호 사용 기한 (순수 도메인, 시계·프레임워크 의존 0).
 *
 * <p>레거시 커머스(ssgb2e-front {@code LoginServiceImpl.selectLogin})는 두 가지를 로그인 흐름 안에서
 * 강제했다: 비밀번호 실패 5 회 누적이면 입장 차단, 마지막 비밀번호 변경 후 90 일이 지나면 변경을 요구.
 * Lemuel 로그인은 BCrypt 로 해시만 비교했을 뿐 <b>시도 횟수를 세지 않았다</b> — 온라인 사전 공격에
 * 무제한 시도를 허용하는 상태였다.
 *
 * <p><b>레거시와 다른 점 하나</b>: 저쪽은 잠기면 관리자/비밀번호 재설정 전까지 영구히 잠긴다. 여기서는
 * 기한부 잠금이다. 영구 잠금은 공격자가 남의 계정 이메일만 알면 마음대로 서비스 거부를 걸 수 있게 한다
 * (계정 잠금 공격). 기한이 지나면 스스로 풀리되, 그 사이 시도는 전부 막힌다.
 *
 * @param maxFailedAttempts 잠금이 걸리는 연속 실패 횟수(1 이상)
 * @param lockDuration      잠금 유지 시간(양수)
 * @param passwordMaxAge    비밀번호 사용 기한. {@link Duration#ZERO} 이면 만료 검사를 하지 않는다
 */
public record LoginSecurityPolicy(int maxFailedAttempts, Duration lockDuration, Duration passwordMaxAge) {

    /** 레거시와 같은 5 회 기준 + 30 분 기한부 잠금 + 90 일 비밀번호 기한. */
    public static final LoginSecurityPolicy DEFAULT =
            new LoginSecurityPolicy(5, Duration.ofMinutes(30), Duration.ofDays(90));

    public LoginSecurityPolicy {
        if (maxFailedAttempts < 1) {
            throw new UserInvariantViolationException(
                    "잠금 임계 실패 횟수는 1 이상이어야 합니다: " + maxFailedAttempts);
        }
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new UserInvariantViolationException(
                    "잠금 유지 시간은 양수여야 합니다: " + lockDuration + " (0·음수는 잠금이 즉시 풀려 무의미)");
        }
        if (passwordMaxAge == null || passwordMaxAge.isNegative()) {
            throw new UserInvariantViolationException(
                    "비밀번호 사용 기한은 음수일 수 없습니다: " + passwordMaxAge);
        }
    }

    /** 비밀번호 만료를 쓰지 않는 정책인지. {@code ZERO} 를 "무제한"으로 읽는 유일한 지점. */
    public boolean checksPasswordAge() {
        return !passwordMaxAge.isZero();
    }
}
