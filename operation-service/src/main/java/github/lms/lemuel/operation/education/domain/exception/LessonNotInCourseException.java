package github.lms.lemuel.operation.education.domain.exception;

/**
 * URL 이 말하는 과정에 그 차시가 속하지 않을 때 던진다.
 *
 * <p>{@code /admin/education/courses/{courseId}/lessons/{lessonId}} 는 "이 과정에 속한 이 차시"라는
 * 소속 관계를 주장한다. 서버가 그 관계를 대조하지 않으면 {@code courseId} 는 장식이 되고, 엉뚱한
 * 과정 아래로 보낸 수정·삭제가 조용히 성공한다 — 실수는 성공으로 위장될 때 가장 늦게 발견된다.
 */
public class LessonNotInCourseException extends RuntimeException {
    public LessonNotInCourseException(String message) { super(message); }
}
