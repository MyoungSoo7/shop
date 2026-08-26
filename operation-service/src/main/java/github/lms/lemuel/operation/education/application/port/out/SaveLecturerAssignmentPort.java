package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.domain.LecturerAssignment;

/** 강사–과정 배정 저장 포트. */
@FunctionalInterface
public interface SaveLecturerAssignmentPort {
    LecturerAssignment save(LecturerAssignment assignment);
}
