package github.lms.lemuel.expirynotice.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 만료 예고 도메인 불변식 위반 — 대상 종류나 만료 시각이 비었다.
 *
 * <p>{@code IllegalArgumentException} 을 대체한다. 상태코드/응답 계약은 400 으로 동일하지만,
 * 그 예외는 JDK·라이브러리 어디서든 올라와 <b>도메인 규칙 위반과 프로그래밍 실수가 한 타입으로 섞인다</b> —
 * 잡는 쪽이 둘을 구분할 수 없다는 뜻이다. 하네스 게이트 {@code OO-DOMAIN-GENERIC-IAE} 가 금융 5서비스
 * 도메인에서 이것을 막는 이유다.
 */
public class ExpiryNoticeInvariantViolationException extends BusinessException {

    public ExpiryNoticeInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
