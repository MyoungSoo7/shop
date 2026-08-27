package github.lms.lemuel.operation.education.domain.exception;

import java.util.UUID;

/**
 * 해제하려는 배정이 없었다. 강사가 없는 것과는 다르다 — 둘을 같은 404 로 뭉치면 화면이 원인을
 * 못 고른다("그런 강사가 없다"와 "그 강사는 이 과정을 안 맡았다"는 다음 행동이 다르다).
 */
public class AssignmentNotFoundException extends RuntimeException {
    public AssignmentNotFoundException(UUID courseId, UUID lecturerId) {
        super("lecturer " + lecturerId + " is not assigned to course " + courseId);
    }
}
