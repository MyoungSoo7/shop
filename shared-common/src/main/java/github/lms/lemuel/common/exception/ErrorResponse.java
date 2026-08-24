package github.lms.lemuel.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 전 서비스 공통 에러 응답 본문.
 *
 * <p>과거 도메인별로 제각각이던 4종의 응답 형태({@code {error}}, {@code {errors}},
 * {@code {timestamp,status,error,message}}, {@code {timestamp,status,errorCode,message}})를
 * 이 단일 스키마로 통일한다. null 필드는 직렬화에서 제외해 응답을 간결하게 유지한다.
 *
 * @param timestamp 발생 시각
 * @param status    HTTP 상태 코드(숫자)
 * @param error     HTTP 상태 사유 문구(예: "Not Found")
 * @param errorCode 비즈니스 에러 코드({@link ErrorCode#code()} 또는 기술 코드)
 * @param message   사람이 읽는 메시지
 * @param details   부가 정보(예: 재고 수량) — 없으면 생략
 * @param errors    필드별 검증 오류({@code field -> message}) — 없으면 생략
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String errorCode,
        String message,
        Map<String, Object> details,
        Map<String, String> errors
) {

    public static ErrorResponse of(HttpStatus status, String errorCode, String message) {
        return new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(),
                errorCode, message, null, null);
    }

    public static ErrorResponse of(HttpStatus status, String errorCode, String message,
                                   Map<String, Object> details) {
        return new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(),
                errorCode, message, details, null);
    }

    public static ErrorResponse validation(HttpStatus status, String errorCode, String message,
                                           Map<String, String> errors) {
        return new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(),
                errorCode, message, null, errors);
    }
}
