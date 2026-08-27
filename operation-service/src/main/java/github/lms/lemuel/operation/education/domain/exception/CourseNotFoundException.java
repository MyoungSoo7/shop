package github.lms.lemuel.operation.education.domain.exception;

import java.util.UUID;

/**
 * 없는 과정을 가리켰다.
 *
 * <p>전에는 {@code CourseAdminService} 의 중첩 클래스였다. 그러면 이 예외를 잡으려는 웹 어댑터가
 * <b>구체 서비스 클래스를 임포트</b>해야 해서, 포트를 아무리 잘 만들어도 어댑터 → 서비스 의존이
 * 남는다. 실제로 {@code EducationExceptionHandler} 는 그 이유 하나로 아키텍처 허용 목록에 올라
 * 있었고, 같은 이유로 {@code EnrollmentAdminService}·{@code LecturerAdminService} 도 남의 서비스
 * 클래스를 임포트하고 있었다 — 과정을 못 찾았다는 사실은 그 셋의 공통 관심사인데, 그걸 담은
 * 타입은 셋 중 하나의 <b>안</b>에 있었다.
 */
public class CourseNotFoundException extends RuntimeException {
    public CourseNotFoundException(UUID id) {
        super("course not found: " + id);
    }
}
