package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.in.ManageCourseUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryCourseUseCase;
import github.lms.lemuel.operation.education.application.port.out.EducationAuditPort;
import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.PublishEducationEventPort;
import github.lms.lemuel.operation.education.application.port.out.SaveCoursePort;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.exception.CourseNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CourseAdminService implements QueryCourseUseCase, ManageCourseUseCase {
    private final LoadCoursePort loadCourse;
    private final SaveCoursePort saveCourse;
    private final PublishEducationEventPort events;
    private final EducationAuditPort audit;

    public CourseAdminService(LoadCoursePort loadCourse, SaveCoursePort saveCourse, PublishEducationEventPort events) {
        this(loadCourse, saveCourse, events, (a, t, id, actor, detail) -> { });
    }

    @Autowired
    public CourseAdminService(LoadCoursePort loadCourse, SaveCoursePort saveCourse,
                              PublishEducationEventPort events, EducationAuditPort audit) {
        this.loadCourse = loadCourse;
        this.saveCourse = saveCourse;
        this.events = events;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public PageSlice<Course> list(CourseStatus status, String query, PageSpec page) {
        return loadCourse.search(status, query == null ? "" : query, page);
    }

    @Override
    @Transactional
    public Course create(SaveCommand command, String actor) {
        Course course = saveCourse.save(
                Course.draft(UUID.randomUUID(), command.title(), command.description(), actor));
        audit.record("COURSE_CREATED", "Course", course.id(), actor, "course created");
        return course;
    }

    @Override
    @Transactional(readOnly = true)
    public Course get(UUID id) { return findOrThrow(id); }

    /** 조회를 애노테이션 없는 내부 메서드로 분리한다 — 쓰기 메서드가 get() 을 자기호출하면 프록시를 우회한다(aop-proxy-gate). */
    private Course findOrThrow(UUID id) {
        return loadCourse.findById(id).orElseThrow(() -> new CourseNotFoundException(id));
    }

    @Override
    @Transactional
    public Course update(UUID id, SaveCommand command, String actor) {
        Course course = findOrThrow(id);
        course.update(command.title(), command.description(), actor);
        Course saved = saveCourse.save(course);
        audit.record("COURSE_UPDATED", "Course", id, actor, "course updated");
        return saved;
    }

    @Override
    @Transactional
    public Course transition(UUID id, CourseStatus target, String actor) {
        Course course = findOrThrow(id);
        switch (target) {
            case PUBLISHED -> course.publish(actor);
            case HIDDEN -> course.hide(actor);
            case CLOSED -> course.close(actor);
            default -> throw new IllegalArgumentException("unsupported course transition");
        }
        Course saved = saveCourse.save(course);
        if (target == CourseStatus.PUBLISHED) events.coursePublished(saved, actor);
        audit.record("COURSE_" + target.name(), "Course", id, actor, "course state transition");
        return saved;
    }
}
