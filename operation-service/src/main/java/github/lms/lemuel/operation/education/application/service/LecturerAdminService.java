package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.in.ManageLecturerUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryLecturerUseCase;
import github.lms.lemuel.operation.education.application.port.out.DeleteLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.EducationAuditPort;
import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.LoadLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.LoadLecturerPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLecturerAssignmentPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLecturerPort;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.Lecturer;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import github.lms.lemuel.operation.education.domain.exception.LecturerAlreadyAssignedException;
import github.lms.lemuel.operation.education.domain.exception.LecturerNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 강사 명부 콘솔 — 등록·수정·활성/비활성·삭제와 과정 배정.
 *
 * <p>dentis 의 lecturer_list · lecturer_Info · lec_proc 묶음이 하던 일이다. 옮기면서 달라진 것이
 * 둘 있다. 첫째, 코드값 대신 표시 문자열을 저장한다(마이그레이션 주석 참조). 둘째, <b>중복 배정을
 * 여기서 막는다</b> — dentis 는 seq 를 max+1 로 발급할 뿐이라 같은 강사가 같은 과정에 두 번
 * 들어갈 수 있었고, 그러면 해제가 한 번에 끝나지 않는다.
 */
@Service
public class LecturerAdminService implements QueryLecturerUseCase, ManageLecturerUseCase {

    private final LoadLecturerPort loadLecturer;
    private final SaveLecturerPort saveLecturer;
    private final LoadLecturerAssignmentPort loadAssignment;
    private final SaveLecturerAssignmentPort saveAssignment;
    private final DeleteLecturerAssignmentPort deleteAssignment;
    private final LoadCoursePort loadCourse;
    private final EducationAuditPort audit;

    public LecturerAdminService(LoadLecturerPort loadLecturer, SaveLecturerPort saveLecturer,
                                LoadLecturerAssignmentPort loadAssignment,
                                SaveLecturerAssignmentPort saveAssignment,
                                DeleteLecturerAssignmentPort deleteAssignment,
                                LoadCoursePort loadCourse) {
        this(loadLecturer, saveLecturer, loadAssignment, saveAssignment, deleteAssignment, loadCourse,
                (a, t, id, actor, detail) -> { });
    }

    @Autowired
    public LecturerAdminService(LoadLecturerPort loadLecturer, SaveLecturerPort saveLecturer,
                                LoadLecturerAssignmentPort loadAssignment,
                                SaveLecturerAssignmentPort saveAssignment,
                                DeleteLecturerAssignmentPort deleteAssignment,
                                LoadCoursePort loadCourse, EducationAuditPort audit) {
        this.loadLecturer = loadLecturer;
        this.saveLecturer = saveLecturer;
        this.loadAssignment = loadAssignment;
        this.saveAssignment = saveAssignment;
        this.deleteAssignment = deleteAssignment;
        this.loadCourse = loadCourse;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public PageSlice<Lecturer> list(String keyword, boolean activeOnly, PageSpec page) {
        return loadLecturer.search(keyword == null ? "" : keyword, activeOnly, page);
    }

    @Override
    @Transactional(readOnly = true)
    public Lecturer get(UUID id) { return lecturerOrThrow(id); }

    @Override
    @Transactional
    public Lecturer register(SaveCommand command, String actor) {
        Lecturer lecturer = saveLecturer.save(Lecturer.register(UUID.randomUUID(), command.name(),
                command.englishName(), command.graduateSchool(), command.officeName(), command.career(),
                command.lecturerType(), command.historyKo(), command.historyEn(), command.etcMemo(),
                command.majors(), command.lectureFields(), actor));
        audit.record("LECTURER_REGISTERED", "Lecturer", lecturer.id(), actor,
                "lecturer registered: " + command.name());
        return lecturer;
    }

    @Override
    @Transactional
    public Lecturer update(UUID id, SaveCommand command, String actor) {
        Lecturer lecturer = lecturerOrThrow(id);
        lecturer.update(command.name(), command.englishName(), command.graduateSchool(),
                command.officeName(), command.career(), command.lecturerType(), command.historyKo(),
                command.historyEn(), command.etcMemo(), command.majors(), command.lectureFields(), actor);
        Lecturer saved = saveLecturer.save(lecturer);
        audit.record("LECTURER_UPDATED", "Lecturer", id, actor, "lecturer updated");
        return saved;
    }

    /** 활성/비활성 토글. 비활성 강사는 새 배정을 받지 않지만 이미 맡은 과정에서 빠지지는 않는다. */
    @Override
    @Transactional
    public Lecturer changeActivation(UUID id, boolean active, String actor) {
        Lecturer lecturer = lecturerOrThrow(id);
        if (active) lecturer.activate(actor); else lecturer.deactivate(actor);
        Lecturer saved = saveLecturer.save(lecturer);
        audit.record(active ? "LECTURER_ACTIVATED" : "LECTURER_DEACTIVATED", "Lecturer", id, actor,
                "lecturer activation changed to " + active);
        return saved;
    }

    /**
     * 명부에서 뺀다. <b>배정은 함께 지우지 않는다</b> — 지우면 진행 중인 과정의 강사 칸이 조용히
     * 비고, 그 과정이 원래 무인이었는지 강사를 뺀 것인지 구분할 수 없게 된다. 배정 해제는 과정별로
     * 눈에 보이게 해야 한다.
     */
    @Override
    @Transactional
    public Lecturer delete(UUID id, String actor) {
        Lecturer lecturer = lecturerOrThrow(id);
        lecturer.delete(actor);
        Lecturer saved = saveLecturer.save(lecturer);
        audit.record("LECTURER_DELETED", "Lecturer", id, actor,
                "lecturer removed from roster (assignments kept: "
                        + loadAssignment.findByLecturer(id).size() + ")");
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LecturerAssignment> assignmentsOfLecturer(UUID lecturerId) {
        lecturerOrThrow(lecturerId);
        return loadAssignment.findByLecturer(lecturerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LecturerAssignment> assignmentsOfCourse(UUID courseId) {
        courseOrThrow(courseId);
        return loadAssignment.findByCourse(courseId);
    }

    @Override
    @Transactional
    public LecturerAssignment assign(UUID lecturerId, UUID courseId, String actor) {
        Lecturer lecturer = lecturerOrThrow(lecturerId);
        Course course = courseOrThrow(courseId);
        lecturer.ensureAssignable();
        if (loadAssignment.exists(courseId, lecturerId)) {
            throw new LecturerAlreadyAssignedException(courseId, lecturerId);
        }
        LecturerAssignment assignment = saveAssignment.save(
                LecturerAssignment.assign(UUID.randomUUID(), courseId, lecturerId, actor));
        audit.record("LECTURER_ASSIGNED", "Lecturer", lecturerId, actor,
                "assigned to course " + course.title());
        return assignment;
    }

    /** 배정을 해제한다. 없던 배정이면 false — 화면이 "이미 해제됨"과 "실패"를 구분할 수 있어야 한다. */
    @Override
    @Transactional
    public boolean unassign(UUID lecturerId, UUID courseId, String actor) {
        lecturerOrThrow(lecturerId);
        boolean removed = deleteAssignment.delete(courseId, lecturerId);
        if (removed) {
            audit.record("LECTURER_UNASSIGNED", "Lecturer", lecturerId, actor,
                    "unassigned from course " + courseId);
        }
        return removed;
    }

    /** 조회를 애노테이션 없는 내부 메서드로 분리한다 — 쓰기 메서드가 get() 을 자기호출하면 프록시를 우회한다(aop-proxy-gate). */
    private Lecturer lecturerOrThrow(UUID id) {
        return loadLecturer.findById(id).orElseThrow(() -> new LecturerNotFoundException(id));
    }

    private Course courseOrThrow(UUID courseId) {
        return loadCourse.findById(courseId).orElseThrow(() -> new CourseNotFoundException(courseId));
    }
}
