package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Lecturer;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.LecturerNotFoundException;

import java.util.List;
import java.util.UUID;

/** 강사 명부·배정 조회 창구. */
public interface QueryLecturerUseCase {

    /**
     * @param keyword    빈 문자열이면 이름 필터 없음. null 도 같게 다룬다.
     * @param activeOnly 배정 가능한 강사만 고를 때 켠다
     */
    PageSlice<Lecturer> list(String keyword, boolean activeOnly, PageSpec page);

    /** @throws LecturerNotFoundException 해당 id 의 강사가 없을 때 */
    Lecturer get(UUID id);

    /** @throws LecturerNotFoundException 해당 id 의 강사가 없을 때 */
    List<LecturerAssignment> assignmentsOfLecturer(UUID lecturerId);

    /** 그 과정에 배정된 강사들 — 과정 화면이 "누가 가르치나"를 물을 때.
     *
     * @throws CourseNotFoundException 해당 과정이 없을 때 */
    List<LecturerAssignment> assignmentsOfCourse(UUID courseId);
}
