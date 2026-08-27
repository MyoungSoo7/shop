package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.in.CapacitySummary;
import github.lms.lemuel.operation.education.application.port.in.ManageEnrollmentUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryEnrollmentUseCase;
import github.lms.lemuel.operation.education.application.port.out.EducationAuditPort;
import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.LoadEnrollmentPort;
import github.lms.lemuel.operation.education.application.port.out.SaveCoursePort;
import github.lms.lemuel.operation.education.application.port.out.SaveEnrollmentPort;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.EnrollmentNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.InvalidCourseStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 수강 신청 콘솔 — 정원·대기·취소를 다룬다.
 *
 * <p>결제는 이 서비스의 관심사가 아니다. 그래서 여기서 하는 일은 셋뿐이다: 자리를 <b>주고</b>,
 * <b>거두고</b>, 얼마나 남았는지 <b>센다</b>.
 */
@Service
public class EnrollmentAdminService implements QueryEnrollmentUseCase, ManageEnrollmentUseCase {

    private final LoadEnrollmentPort loadEnrollment;
    private final SaveEnrollmentPort saveEnrollment;
    private final LoadCoursePort loadCourse;
    private final SaveCoursePort saveCourse;
    private final EducationAuditPort audit;

    public EnrollmentAdminService(LoadEnrollmentPort loadEnrollment, SaveEnrollmentPort saveEnrollment,
                                  LoadCoursePort loadCourse, SaveCoursePort saveCourse) {
        this(loadEnrollment, saveEnrollment, loadCourse, saveCourse, (a, t, id, actor, detail) -> { });
    }

    @Autowired
    public EnrollmentAdminService(LoadEnrollmentPort loadEnrollment, SaveEnrollmentPort saveEnrollment,
                                  LoadCoursePort loadCourse, SaveCoursePort saveCourse, EducationAuditPort audit) {
        this.loadEnrollment = loadEnrollment;
        this.saveEnrollment = saveEnrollment;
        this.loadCourse = loadCourse;
        this.saveCourse = saveCourse;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public PageSlice<Enrollment> list(UUID courseId, EnrollmentStatus status, String keyword, PageSpec page) {
        return loadEnrollment.search(courseId, status, keyword == null ? "" : keyword, page);
    }

    /**
     * 그 과정의 자리 현황. 화면이 "정원 30 / 확정 28 / 대기 5" 를 한 줄로 보여 주려면 세 수가
     * 같은 시각에 읽혀야 한다 — 화면이 세 번 호출해 조립하면 그 사이 확정이 들어와도 모른다.
     */
    @Override
    @Transactional(readOnly = true)
    public CapacitySummary summary(UUID courseId) {
        Course course = courseOrThrow(courseId);
        return new CapacitySummary(
                courseId, course.title(), course.capacity(),
                loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED),
                loadEnrollment.countByStatus(courseId, EnrollmentStatus.WAITING),
                loadEnrollment.countByStatus(courseId, EnrollmentStatus.CANCELLED));
    }

    /**
     * 신청을 접수한다 — 언제나 WAITING 이다. 자리를 주는 것은 {@link #confirm} 이다.
     *
     * <p>끝난 과정에는 받지 않는다. CLOSED 는 "더는 이 과정으로 사람을 받지 않는다"는 선언이라,
     * 여기에 신청을 꽂으면 아무도 확정해 주지 않을 대기표를 발급하는 셈이 된다.
     */
    @Override
    @Transactional
    public Enrollment register(RegisterCommand command, String actor) {
        Course course = courseOrThrow(command.courseId());
        if (course.status() == CourseStatus.CLOSED) {
            throw new InvalidCourseStateException("closed course does not accept enrollments");
        }
        Enrollment enrollment = saveEnrollment.save(Enrollment.apply(
                UUID.randomUUID(), command.courseId(), command.applicantId(), command.applicantName(),
                command.applicantOrganization(), actor));
        audit.record("ENROLLMENT_REGISTERED", "Enrollment", enrollment.id(), actor,
                "enrollment registered for course " + command.courseId());
        return enrollment;
    }

    @Override
    @Transactional(readOnly = true)
    public Enrollment get(UUID id) { return enrollmentOrThrow(id); }

    /** 자리를 확정한다. 정원이 찼으면 거절한다 — 초과 확정은 되돌릴 때 누군가를 골라 내쫓는 일이 된다. */
    @Override
    @Transactional
    public Enrollment confirm(UUID id, String actor) {
        Enrollment enrollment = enrollmentOrThrow(id);
        Course course = courseOrThrow(enrollment.courseId());
        course.ensureSeatAvailable((int) loadEnrollment.countByStatus(enrollment.courseId(), EnrollmentStatus.CONFIRMED));
        enrollment.confirm(actor);
        Enrollment saved = saveEnrollment.save(enrollment);
        audit.record("ENROLLMENT_CONFIRMED", "Enrollment", id, actor, "enrollment confirmed");
        return saved;
    }

    @Override
    @Transactional
    public Enrollment cancel(UUID id, String reason, String actor) {
        Enrollment enrollment = enrollmentOrThrow(id);
        enrollment.cancel(reason, actor);
        Enrollment saved = saveEnrollment.save(enrollment);
        audit.record("ENROLLMENT_CANCELLED", "Enrollment", id, actor, "enrollment cancelled: " + reason);
        return saved;
    }

    @Override
    @Transactional
    public Enrollment correct(UUID id, String applicantName, String applicantOrganization, String actor) {
        Enrollment enrollment = enrollmentOrThrow(id);
        enrollment.correct(applicantName, applicantOrganization, actor);
        Enrollment saved = saveEnrollment.save(enrollment);
        audit.record("ENROLLMENT_CORRECTED", "Enrollment", id, actor, "applicant information corrected");
        return saved;
    }

    @Override
    @Transactional
    public Enrollment memo(UUID id, String memo, String actor) {
        Enrollment enrollment = enrollmentOrThrow(id);
        enrollment.memo(memo, actor);
        Enrollment saved = saveEnrollment.save(enrollment);
        audit.record("ENROLLMENT_MEMO", "Enrollment", id, actor, "admin memo updated");
        return saved;
    }

    /** 정원을 바꾼다. 확정 인원보다 작게 줄이는 요청은 도메인이 거절한다. */
    @Override
    @Transactional
    public CapacitySummary changeCapacity(UUID courseId, Integer capacity, String actor) {
        Course course = courseOrThrow(courseId);
        long confirmed = loadEnrollment.countByStatus(courseId, EnrollmentStatus.CONFIRMED);
        course.changeCapacity(capacity, (int) confirmed, actor);
        saveCourse.save(course);
        audit.record("COURSE_CAPACITY_CHANGED", "Course", courseId, actor,
                "capacity changed to " + (capacity == null ? "unlimited" : capacity));
        return new CapacitySummary(courseId, course.title(), capacity, confirmed,
                loadEnrollment.countByStatus(courseId, EnrollmentStatus.WAITING),
                loadEnrollment.countByStatus(courseId, EnrollmentStatus.CANCELLED));
    }

    /** 조회를 애노테이션 없는 내부 메서드로 분리한다 — 쓰기 메서드가 get() 을 자기호출하면 프록시를 우회한다(aop-proxy-gate). */
    private Enrollment enrollmentOrThrow(UUID id) {
        return loadEnrollment.findById(id).orElseThrow(() -> new EnrollmentNotFoundException(id));
    }

    private Course courseOrThrow(UUID courseId) {
        return loadCourse.findById(courseId).orElseThrow(() -> new CourseNotFoundException(courseId));
    }
}
