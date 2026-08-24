package github.lms.lemuel.operation.notification.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 알림 슬라이스 전용 예외 매핑.
 *
 * <p>{@code assignableTypes} 로 이 슬라이스의 컨트롤러에만 범위를 묶는다 — operation-service 에는
 * incident·signal 컨트롤러가 함께 살고, 범위를 안 묶으면 이 어드바이스가 그쪽의
 * {@link IllegalArgumentException} 까지 400 으로 가로채 기존 매핑을 바꿔 버린다.
 */
@RestControllerAdvice(assignableTypes = {NotificationController.class, NotificationStreamController.class})
public class NotificationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationExceptionHandler.class);

    /** 클라이언트 검증 실패의 안정적인 에러 바디. */
    public record ErrorResponse(int status, String error, String message) {
    }

    /**
     * 도메인 검증 실패(공백 수신자·제목 등)를 불투명한 500 대신 400 으로 매핑한다.
     * 인프라 실패는 그대로 500 으로 흐른다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleValidation(IllegalArgumentException ex) {
        log.warn("validation rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "BAD_REQUEST",
                        ex.getMessage() == null ? "invalid request" : ex.getMessage()));
    }

    /**
     * 검증된 신원 없음 — 401, 500 이 아니다. 사유는 일반화한다: 호출자는 자신이 인증되지 않았다는
     * 것만 알면 되고, 토큰이 왜 실패했는지는 알 필요가 없다(정보 노출).
     */
    @ExceptionHandler(StreamUnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(StreamUnauthorizedException ex) {
        log.debug("stream rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(401, "UNAUTHORIZED", "authentication required"));
    }

    /**
     * 서명 키 미설정 — 푸시 스트림만 꺼지고(503) 나머지 서비스는 계속 서빙한다. Fail-closed 지 fail-open 이 아니다.
     */
    @ExceptionHandler(StreamNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleNotConfigured(StreamNotConfiguredException ex) {
        log.warn("stream unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(503, "STREAM_NOT_CONFIGURED", "notification stream is not available"));
    }
}
