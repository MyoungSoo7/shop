package github.lms.lemuel.operation.education.domain.exception;

import java.util.UUID;

/** 없는 강사를 가리켰다. 서비스 중첩 클래스였던 이유와 옮긴 이유는 {@link CourseNotFoundException} 참조. */
public class LecturerNotFoundException extends RuntimeException {
    public LecturerNotFoundException(UUID id) {
        super("lecturer not found: " + id);
    }
}
