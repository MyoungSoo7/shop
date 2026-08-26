package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * 수강 신청 영속 모델 — 매핑만 한다. 전이 규칙은 도메인 {@link Enrollment} 가 소유한다
 * ({@code CourseJpaEntity} 에 규칙이 복제돼 도메인이 죽은 코드가 됐던 전례를 반복하지 않는다).
 */
@Entity
@Table(name = "education_enrollments", schema = "education")
public class EnrollmentJpaEntity {
    @Id private UUID id;
    private UUID courseId;
    private String applicantId;
    private String applicantName;
    private String applicantOrganization;
    @Enumerated(EnumType.STRING) private EnrollmentStatus status;
    private String adminMemo;
    private String cancelReason;
    private Instant appliedAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected EnrollmentJpaEntity() { }

    static EnrollmentJpaEntity fromDomain(Enrollment enrollment) {
        EnrollmentJpaEntity entity = new EnrollmentJpaEntity();
        entity.id = enrollment.id();
        entity.courseId = enrollment.courseId();
        entity.applicantId = enrollment.applicantId();
        entity.appliedAt = enrollment.appliedAt();
        entity.createdBy = enrollment.updatedBy();
        entity.createdAt = Instant.now();
        entity.sync(enrollment);
        return entity;
    }

    /** 도메인 상태를 영속 모델에 반영한다 — 식별자·과정·신청자 키·접수 시각은 건드리지 않는다. */
    void sync(Enrollment enrollment) {
        this.applicantName = enrollment.applicantName();
        this.applicantOrganization = enrollment.applicantOrganization();
        this.status = enrollment.status();
        this.adminMemo = enrollment.adminMemo();
        this.cancelReason = enrollment.cancelReason();
        this.confirmedAt = enrollment.confirmedAt();
        this.cancelledAt = enrollment.cancelledAt();
        this.updatedBy = enrollment.updatedBy();
        this.updatedAt = Instant.now();
    }

    Enrollment toDomain() {
        return Enrollment.rehydrate(id, courseId, applicantId, applicantName, applicantOrganization,
                status, adminMemo, cancelReason, appliedAt, confirmedAt, cancelledAt, updatedBy, version);
    }

    public UUID getId() { return id; }
}
