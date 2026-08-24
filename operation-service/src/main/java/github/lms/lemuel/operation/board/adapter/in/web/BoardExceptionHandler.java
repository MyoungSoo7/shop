package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardAttachmentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardCommentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.DuplicateBoardKeyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 게시판 API 예외 → HTTP 상태 매핑.
 *
 * <p>도메인 예외를 그대로 500 으로 흘리면 "게시판 키가 중복입니다" 같은 <b>사용자가 고칠 수 있는
 * 오류</b>가 서버 장애처럼 보인다. 매핑은 이 한 곳에만 둔다.
 *
 * <p>catch-all({@code Exception}) 핸들러는 두지 않는다 — 그런 핸들러가 있으면 스프링 시큐리티의
 * {@code AccessDeniedException} 까지 삼켜 403 이 500 으로 바뀐다(정산 쪽에서 실제로 겪은 함정).
 *
 * <p>{@code @Order} 를 명시한 이유: shared-common 의 {@code GlobalExceptionHandler} 와
 * {@code MethodArgumentNotValidException}·{@code MaxUploadSizeExceededException} 을 함께 다룬다.
 * 둘 다 순서를 안 정하면 기본값이 같아(LOWEST_PRECEDENCE) 어느 advice 가 이길지 빈 등록 순서에
 * 달린다 — 빌드에 따라 응답 스키마가 바뀔 수 있다는 뜻이다. 게시판 전용 메시지를 유지하기 위해
 * 이 advice 를 명시적으로 앞에 둔다.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RestControllerAdvice
public class BoardExceptionHandler {

    @ExceptionHandler({BoardNotFoundException.class, BoardPostNotFoundException.class,
            BoardCommentNotFoundException.class, BoardAttachmentNotFoundException.class})
    public ResponseEntity<Map<String, String>> handleNotFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    /**
     * 업로드 크기 초과 → 413.
     *
     * <p>서블릿 단에서 끊긴 요청이라 도메인까지 오지 않는다. 이걸 매핑하지 않으면 사용자는
     * 원인을 알 수 없는 500 을 본다 — 고칠 수 있는 오류는 고칠 수 있게 말해 줘야 한다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "첨부 파일이 허용 크기를 넘습니다."));
    }

    /**
     * 권한 없음 → 403.
     *
     * <p>읽기 경로는 여기 오지 않는다 — 볼 수 없는 대상은 404 로 답한다(존재 노출 차단).
     * 이 예외는 대상의 존재를 이미 아는 주체의 쓰기·수정·삭제에만 쓰인다.
     */
    @ExceptionHandler(BoardAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(BoardAccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(DuplicateBoardKeyException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateBoardKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(BoardInvariantViolationException.class)
    public ResponseEntity<Map<String, String>> handleInvariant(BoardInvariantViolationException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("message", message.isEmpty() ? "요청이 올바르지 않습니다." : message));
    }

    /**
     * 본문 파싱 실패 → 400.
     *
     * <p>독립 서비스 시절엔 기본 ObjectMapper 가 primitive 누락을 false 로 눙쳐 {@code @Valid}
     * 까지 갔지만, operation 흡수 후 공용 매퍼는 {@code FAIL_ON_NULL_FOR_PRIMITIVES} 라 파싱
     * 단계에서 끊긴다. 어느 쪽이든 클라이언트 입력 문제이므로 400 계약을 유지한다 — 매핑하지
     * 않으면 catch-all 이 500 으로 삼킨다.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 본문이 올바르지 않습니다."));
    }
}
