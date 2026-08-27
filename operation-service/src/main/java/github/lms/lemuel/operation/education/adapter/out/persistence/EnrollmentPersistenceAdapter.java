package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.out.LoadEnrollmentPort;
import github.lms.lemuel.operation.education.application.port.out.SaveEnrollmentPort;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 수강 신청 영속 어댑터 — Spring Data 타입은 이 경계 안에서만 쓴다. */
@Component
public class EnrollmentPersistenceAdapter implements LoadEnrollmentPort, SaveEnrollmentPort {

    private final EnrollmentRepository enrollments;

    public EnrollmentPersistenceAdapter(EnrollmentRepository enrollments) { this.enrollments = enrollments; }

    @Override
    public Optional<Enrollment> findById(UUID id) {
        return enrollments.findById(id).map(EnrollmentJpaEntity::toDomain);
    }

    @Override
    public PageSlice<Enrollment> search(UUID courseId, EnrollmentStatus status, String keyword, PageSpec page) {
        // 접수 순서 오름차순이 기본이다 — 대기 목록에서 다음 사람이 누구인지가 이 화면의 핵심 질문이고,
        // 정렬이 없으면 페이지마다 순서가 달라져 같은 사람이 두 번 보이거나 아예 빠진다.
        Pageable pageable = PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.ASC, "appliedAt"));
        Page<EnrollmentJpaEntity> found = enrollments.search(courseId, status, keyword, pageable);
        return new PageSlice<>(found.getContent().stream().map(EnrollmentJpaEntity::toDomain).toList(),
                page.page(), page.size(), found.getTotalElements());
    }

    @Override
    public long countByStatus(UUID courseId, EnrollmentStatus status) {
        return enrollments.countByCourseIdAndStatus(courseId, status);
    }

    @Override
    public Enrollment save(Enrollment enrollment) {
        EnrollmentJpaEntity entity = enrollments.findById(enrollment.id()).orElse(null);
        if (entity == null) {
            entity = EnrollmentJpaEntity.fromDomain(enrollment);
        } else {
            entity.sync(enrollment);
        }
        return enrollments.save(entity).toDomain();
    }
}
