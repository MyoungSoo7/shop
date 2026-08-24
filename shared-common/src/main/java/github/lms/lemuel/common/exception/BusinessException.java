package github.lms.lemuel.common.exception;

import java.util.Map;

/**
 * 도메인 비즈니스 예외의 공통 베이스.
 *
 * <p>{@link ErrorCode} 를 들고 던져지며, {@code GlobalExceptionHandler} 의 단일
 * {@code @ExceptionHandler(BusinessException.class)} 가 코드→HTTP 상태/응답으로 변환한다.
 * 각 도메인 예외는 이 클래스를 상속하고 적절한 {@code ErrorCode} 만 전달하면 되므로,
 * 새 예외마다 핸들러를 추가할 필요가 없다.
 *
 * <p>여전히 {@link RuntimeException} 하위라 기존 {@code catch}/{@code assertThrows} 와 호환된다.
 * {@code message} 가 null 이면 {@code ErrorCode} 의 기본 메시지를 사용한다.
 * {@code details} 는 응답에 함께 노출할 부가 정보(예: 재고 수량)를 위한 선택적 맵이다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), (Map<String, Object>) null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, (Map<String, Object>) null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message != null ? message : errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message != null ? message : errorCode.defaultMessage(), cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
