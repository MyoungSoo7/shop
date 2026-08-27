package github.lms.lemuel.operation.education.domain.exception;

import java.util.UUID;

/** 없는 수강 신청을 가리켰다. 서비스 중첩 클래스였던 이유와 옮긴 이유는 {@link CourseNotFoundException} 참조. */
public class EnrollmentNotFoundException extends RuntimeException {
    public EnrollmentNotFoundException(UUID id) {
        super("enrollment not found: " + id);
    }
}
