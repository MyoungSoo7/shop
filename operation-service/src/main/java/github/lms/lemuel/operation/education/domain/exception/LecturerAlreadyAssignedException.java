package github.lms.lemuel.operation.education.domain.exception;

import java.util.UUID;

/**
 * 같은 강사를 같은 과정에 두 번 배정하려 했다. DB 유니크 제약과 같은 규칙이지만, 제약 위반이
 * DataIntegrityViolationException 으로 올라오면 500 이 된다 — 실제로는 운영자가 고칠 수 있는 409 다.
 */
public class LecturerAlreadyAssignedException extends RuntimeException {
    public LecturerAlreadyAssignedException(UUID courseId, UUID lecturerId) {
        super("lecturer " + lecturerId + " is already assigned to course " + courseId);
    }
}
