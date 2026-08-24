package github.lms.lemuel.user.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 비밀번호 사용 기한이 지나 재설정 없이는 로그인할 수 없을 때.
 *
 * <p>비밀번호 <b>검증에 성공한 뒤</b>에만 던진다. 검증 전에 던지면 "이 이메일은 실재하고 비밀번호가
 * 오래됐다"는 사실이 아무에게나 새어 나가는 계정 열거(enumeration) 통로가 된다.
 */
public class PasswordExpiredException extends BusinessException {

    public PasswordExpiredException(long maxAgeDays) {
        super(ErrorCode.PASSWORD_EXPIRED,
                "비밀번호를 " + maxAgeDays + "일 이상 변경하지 않았습니다. 비밀번호를 재설정한 뒤 로그인해주세요.");
    }
}
