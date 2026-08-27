package github.lms.lemuel.operation.education.application.port.in;

import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.exception.CourseCapacityExceededException;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.EnrollmentNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import github.lms.lemuel.operation.education.domain.exception.InvalidEnrollmentStateException;

import java.util.UUID;

/**
 * 수강 신청 접수·확정·취소 창구.
 *
 * <p>결제는 여기 없다. 이 창구가 하는 일은 셋뿐이다 — 자리를 <b>주고</b>, <b>거두고</b>,
 * 얼마나 남았는지 <b>센다</b>.
 */
public interface ManageEnrollmentUseCase {

    /**
     * 신청을 접수한다 — 언제나 WAITING 이다. 자리를 주는 것은 {@link #confirm} 이다.
     *
     * @throws CourseNotFoundException     해당 과정이 없을 때
     * @throws InvalidCourseStateException 끝난(CLOSED) 과정일 때 — 아무도 확정해 주지 않을
     *                                     대기표를 발급하지 않는다
     */
    Enrollment register(RegisterCommand command, String actor);

    /**
     * 자리를 확정한다.
     *
     * @throws EnrollmentNotFoundException     해당 id 의 신청이 없을 때
     * @throws CourseCapacityExceededException 정원이 찼을 때 — 초과 확정은 되돌릴 때
     *                                         누군가를 골라 내쫓는 일이 된다
     */
    Enrollment confirm(UUID id, String actor);

    /** @throws InvalidEnrollmentStateException 이미 취소된 신청일 때 */
    Enrollment cancel(UUID id, String reason, String actor);

    /** 신청자 정보를 고친다. 신청 자체의 상태는 건드리지 않는다. */
    Enrollment correct(UUID id, String applicantName, String applicantOrganization, String actor);

    /** 운영자 메모. 신청자에게는 보이지 않는다. */
    Enrollment memo(UUID id, String memo, String actor);

    /**
     * 정원을 바꾼다. 바뀐 뒤의 자리 현황을 돌려주므로 화면이 다시 조회할 필요가 없다.
     *
     * @param capacity null 이면 정원 없음. 0 은 "아무도 안 받음" 이라 뜻이 다르다.
     * @throws CourseCapacityExceededException 확정 인원보다 작게 줄이려 할 때
     */
    CapacitySummary changeCapacity(UUID courseId, Integer capacity, String actor);

    record RegisterCommand(UUID courseId, String applicantId, String applicantName,
                           String applicantOrganization) {
    }
}
