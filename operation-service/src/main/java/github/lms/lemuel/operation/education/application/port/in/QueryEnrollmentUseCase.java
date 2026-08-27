package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.EnrollmentNotFoundException;

import java.util.UUID;

/** 수강 신청 조회 창구. */
public interface QueryEnrollmentUseCase {

    /** @param keyword 빈 문자열이면 신청자 필터 없음. null 도 같게 다룬다. */
    PageSlice<Enrollment> list(UUID courseId, EnrollmentStatus status, String keyword, PageSpec page);

    /** @throws EnrollmentNotFoundException 해당 id 의 신청이 없을 때 */
    Enrollment get(UUID id);

    /** @throws CourseNotFoundException 해당 과정이 없을 때 */
    CapacitySummary summary(UUID courseId);
}
