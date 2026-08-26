package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 강사–과정 배정 영속 모델. 상태가 없으므로 sync 도 없다 — 만들거나 지우거나 둘뿐이다. */
@Entity
@Table(name = "education_lecturer_assignments", schema = "education")
public class LecturerAssignmentJpaEntity {

    @Id private UUID id;
    private UUID courseId;
    private UUID lecturerId;
    private Instant assignedAt;
    private String createdBy;

    protected LecturerAssignmentJpaEntity() { }

    static LecturerAssignmentJpaEntity fromDomain(LecturerAssignment assignment) {
        LecturerAssignmentJpaEntity entity = new LecturerAssignmentJpaEntity();
        entity.id = assignment.id();
        entity.courseId = assignment.courseId();
        entity.lecturerId = assignment.lecturerId();
        entity.assignedAt = assignment.assignedAt();
        entity.createdBy = assignment.assignedBy();
        return entity;
    }

    LecturerAssignment toDomain() {
        return new LecturerAssignment(id, courseId, lecturerId, assignedAt, createdBy, null, null);
    }

    public UUID getId() { return id; }

    UUID getCourseId() { return courseId; }

    UUID getLecturerId() { return lecturerId; }
}
