package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.operation.education.application.service.CourseAdminService.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import github.lms.lemuel.operation.education.domain.exception.LessonNotInCourseException;
import github.lms.lemuel.operation.education.domain.exception.LessonOrderViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * education 도메인 예외 → HTTP 번역. shared-common {@code GlobalExceptionHandler}(LOWEST_PRECEDENCE)
 * 보다 먼저 잡아 도메인 고유의 상태/코드를 준다.
 *
 * <p>응답 본문은 전 서비스 공통 {@link ErrorResponse} 스키마다. 예전에는 이 advice 만
 * {@code Map.of("code", ..., "message", ...)} 를 돌려줘서, 상태 코드는 맞는데 필드 이름이 달라
 * ({@code code} vs {@code errorCode}) 공용 클라이언트가 education 만 따로 파싱해야 했다.
 *
 * <p>도메인 예외 두 개({@link InvalidCourseStateException}·{@link LessonOrderViolationException})는
 * 여기서 매핑하지 않으면 공통 catch-all 을 타고 <b>500</b> 이 된다 — 실제로는 클라이언트가 고칠 수
 * 있는 4xx 다. 매핑을 도메인이 아니라 이 어댑터에 두는 이유는 education 도메인이 스프링을
 * 의존하지 않기 때문이다(ArchUnit 강제) — {@code BusinessException} 상속은 그 경계를 깬다.
 */
@RestControllerAdvice
public class EducationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EducationExceptionHandler.class);

    @ExceptionHandler(CourseNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(CourseNotFoundException exception) {
        return translate(ErrorCode.COURSE_NOT_FOUND, exception);
    }

    @ExceptionHandler(InvalidCourseStateException.class)
    public ResponseEntity<ErrorResponse> invalidState(InvalidCourseStateException exception) {
        return translate(ErrorCode.COURSE_INVALID_STATE, exception);
    }

    @ExceptionHandler(LessonOrderViolationException.class)
    public ResponseEntity<ErrorResponse> lessonOrder(LessonOrderViolationException exception) {
        return translate(ErrorCode.LESSON_ORDER_INVALID, exception);
    }

    @ExceptionHandler(LessonNotInCourseException.class)
    public ResponseEntity<ErrorResponse> lessonNotInCourse(LessonNotInCourseException exception) {
        return translate(ErrorCode.LESSON_NOT_IN_COURSE, exception);
    }

    private ResponseEntity<ErrorResponse> translate(ErrorCode code, Exception exception) {
        log.warn("[{}] {}", code.code(), exception.getMessage());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), exception.getMessage()));
    }
}
