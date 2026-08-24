package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.Lesson;
import github.lms.lemuel.operation.education.domain.LessonContentType;
import github.lms.lemuel.operation.education.domain.LessonStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** 차시 영속 모델 — 매핑만 한다(규칙은 도메인 {@link Lesson}). */
@Entity
@Table(name = "education_lessons", schema = "education")
public class LessonJpaEntity {
    @Id private UUID id;
    private UUID courseId;
    private String title;
    private String description;
    private int sequence;
    @Enumerated(EnumType.STRING) private LessonContentType contentType;
    private String contentRef;
    private boolean required;
    @Enumerated(EnumType.STRING) private LessonStatus status;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected LessonJpaEntity() { }

    static LessonJpaEntity fromDomain(Lesson lesson) {
        LessonJpaEntity entity = new LessonJpaEntity();
        entity.id = lesson.id();
        entity.courseId = lesson.courseId();
        entity.status = lesson.status();
        entity.createdBy = lesson.updatedBy();
        entity.createdAt = Instant.now();
        entity.sync(lesson);
        return entity;
    }

    void sync(Lesson lesson) {
        this.title = lesson.title();
        this.description = lesson.description();
        this.sequence = lesson.sequence();
        this.contentType = lesson.contentType();
        this.contentRef = lesson.contentRef();
        this.required = lesson.required();
        this.updatedBy = lesson.updatedBy();
        this.updatedAt = Instant.now();
    }

    Lesson toDomain() {
        return Lesson.rehydrate(id, courseId, title, description, sequence, contentType, contentRef,
                required, status, updatedBy, version);
    }

    public UUID getId() { return id; }
}
