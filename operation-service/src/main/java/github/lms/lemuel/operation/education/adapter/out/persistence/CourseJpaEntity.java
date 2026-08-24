package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * 과정 영속 모델 — 매핑만 한다.
 *
 * <p>전이 규칙(publish/hide/close)은 도메인 {@link Course} 가 소유한다. 예전에는 같은 규칙이
 * 여기에도 복제돼 있었고 서비스가 이쪽을 호출해서 도메인 모델이 죽은 코드였다.
 */
@Entity
@Table(name = "education_courses", schema = "education")
public class CourseJpaEntity {
    @Id private UUID id;
    private String title;
    private String description;
    @Enumerated(EnumType.STRING) private CourseStatus status;
    private Instant publishedAt;
    private Instant closedAt;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected CourseJpaEntity() { }

    static CourseJpaEntity fromDomain(Course course) {
        CourseJpaEntity entity = new CourseJpaEntity();
        entity.id = course.id();
        entity.createdBy = course.updatedBy();
        entity.createdAt = Instant.now();
        entity.sync(course);
        return entity;
    }

    /** 도메인 상태를 영속 모델에 반영한다 — 식별자·생성 이력은 건드리지 않는다. */
    void sync(Course course) {
        this.title = course.title();
        this.description = course.description();
        this.status = course.status();
        this.publishedAt = course.publishedAt();
        this.closedAt = course.closedAt();
        this.updatedBy = course.updatedBy();
        this.updatedAt = Instant.now();
    }

    Course toDomain() {
        return Course.rehydrate(id, title, description, status, publishedAt, closedAt, updatedBy, version);
    }

    public UUID getId() { return id; }
}
