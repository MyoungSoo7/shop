package github.lms.lemuel.user.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 연속 로그인 실패로 계정이 기한부 잠금 상태일 때.
 *
 * <p>메시지에 <b>해제 시각만</b> 담는다. 남은 시도 횟수를 알려 주면 공격자에게 잠금 임계를 그대로
 * 노출해 임계 직전까지만 두드리는 회피를 쉽게 만든다.
 */
public class AccountLockedException extends BusinessException {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public AccountLockedException(LocalDateTime lockedUntil) {
        super(ErrorCode.ACCOUNT_LOCKED,
                "연속 로그인 실패로 계정이 잠겼습니다. " + FORMAT.format(lockedUntil) + " 이후 다시 시도해주세요.");
    }
}
