package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.out.LoadCoursePort;
import github.lms.lemuel.operation.education.application.port.out.SaveCoursePort;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 과정 영속 어댑터 — Spring Data 타입은 이 경계 안에서만 쓴다. */
@Component
public class CoursePersistenceAdapter implements LoadCoursePort, SaveCoursePort {

    private final CourseRepository courses;

    public CoursePersistenceAdapter(CourseRepository courses) { this.courses = courses; }

    @Override
    public Optional<Course> findById(UUID id) {
        return courses.findById(id).map(CourseJpaEntity::toDomain);
    }

    @Override
    public PageSlice<Course> search(CourseStatus status, String titleKeyword, PageSpec page) {
        Pageable pageable = PageRequest.of(page.page(), page.size());
        Page<CourseJpaEntity> found = status == null
                ? courses.findByTitleContainingIgnoreCase(titleKeyword, pageable)
                : courses.findByStatusAndTitleContainingIgnoreCase(status, titleKeyword, pageable);
        return new PageSlice<>(found.getContent().stream().map(CourseJpaEntity::toDomain).toList(),
                page.page(), page.size(), found.getTotalElements());
    }

    @Override
    public Course save(Course course) {
        CourseJpaEntity entity = courses.findById(course.id()).orElse(null);
        if (entity == null) {
            entity = CourseJpaEntity.fromDomain(course);
        } else {
            entity.sync(course);
        }
        return courses.save(entity).toDomain();
    }
}
