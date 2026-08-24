package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.operation.education.application.service.CourseAdminService.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import github.lms.lemuel.operation.education.domain.exception.LessonNotInCourseException;
import github.lms.lemuel.operation.education.domain.exception.LessonOrderViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * education 도메인 예외 → HTTP 매핑. 응답 본문은 전 서비스 공통 {@link ErrorResponse} 스키마다.
 *
 * <p>예전 이 advice 는 {@code Map.of("code", ..., "message", ...)} 를 돌려줬다. 상태 코드는 맞았지만
 * 필드 이름이 달라({@code code} vs {@code errorCode}) 공용 클라이언트가 education 만 따로 파싱해야
 * 했다 — 스키마를 통일하는 이유가 그것이다.
 *
 * <p>핸들러 메서드를 직접 부르지 않고 {@link ExceptionHandlerMethodResolver} 로 "디스패치가 무엇을
 * 고르는가" 를 거쳐 부른다. 그래야 {@code @ExceptionHandler} 애노테이션이 사라지면 테스트가 깨진다 —
 * 메서드만 직접 부르면 매핑이 끊겨도 초록이다.
 */
class EducationExceptionHandlerTest {

    private final EducationExceptionHandler handler = new EducationExceptionHandler();
    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(EducationExceptionHandler.class);

    @Test
    @DisplayName("없는 과정은 404 + COURSE_NOT_FOUND")
    void courseNotFoundMapsTo404() throws Exception {
        ErrorResponse body = dispatch(new CourseNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND);
        assertThat(body.errorCode()).isEqualTo("COURSE_NOT_FOUND");
    }

    @Test
    @DisplayName("허용되지 않는 상태 전이는 400 + COURSE_INVALID_STATE — catch-all(500)로 새지 않는다")
    void invalidCourseStateMapsTo400() throws Exception {
        ErrorResponse body = dispatch(
                new InvalidCourseStateException("course cannot transition from CLOSED"), HttpStatus.BAD_REQUEST);
        assertThat(body.errorCode()).isEqualTo("COURSE_INVALID_STATE");
    }

    @Test
    @DisplayName("차시 순서 위반은 400 + LESSON_ORDER_INVALID — 클라이언트 입력 오류다")
    void lessonOrderViolationMapsTo400() throws Exception {
        ErrorResponse body = dispatch(
                new LessonOrderViolationException("lesson order must contain each course lesson exactly once"),
                HttpStatus.BAD_REQUEST);
        assertThat(body.errorCode()).isEqualTo("LESSON_ORDER_INVALID");
    }

    @Test
    @DisplayName("경로의 과정과 차시 소속이 어긋나면 404 + LESSON_NOT_IN_COURSE — 403 이면 존재가 샌다")
    void lessonNotInCourseMapsTo404() throws Exception {
        ErrorResponse body = dispatch(
                new LessonNotInCourseException("lesson does not belong to course"), HttpStatus.NOT_FOUND);
        assertThat(body.errorCode()).isEqualTo("LESSON_NOT_IN_COURSE");
    }

    /** 디스패치가 고르는 핸들러를 태워 응답을 얻는다 — 매핑이 없으면 여기서 실패한다. */
    private ErrorResponse dispatch(Exception ex, HttpStatus expected) throws Exception {
        Method resolved = resolver.resolveMethod(ex);
        assertThat(resolved).as("%s 를 처리할 핸들러", ex.getClass().getSimpleName()).isNotNull();

        Object result = resolved.invoke(handler, ex);
        assertThat(result).isInstanceOf(ResponseEntity.class);

        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(expected);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);

        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.status()).isEqualTo(expected.value());
        return body;
    }
}
