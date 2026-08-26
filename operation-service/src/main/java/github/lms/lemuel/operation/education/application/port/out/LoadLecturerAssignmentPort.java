package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.domain.LecturerAssignment;

import java.util.List;
import java.util.UUID;

/** 강사–과정 배정 조회 포트. */
public interface LoadLecturerAssignmentPort {

    /** 그 강사가 맡은 과정들 — 배정 순서(오래된 것부터)다. */
    List<LecturerAssignment> findByLecturer(UUID lecturerId);

    /** 그 과정에 배정된 강사들. */
    List<LecturerAssignment> findByCourse(UUID courseId);

    boolean exists(UUID courseId, UUID lecturerId);
}
