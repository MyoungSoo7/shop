package github.lms.lemuel.operation.education.application.port.out;

import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;

import java.util.Optional;
import java.util.UUID;

/** 수강 신청 조회 포트 — 저장 의도({@link SaveEnrollmentPort})와 분리한다(ISP). */
public interface LoadEnrollmentPort {

    Optional<Enrollment> findById(UUID id);

    /**
     * 신청을 찾는다. {@code courseId} 가 null 이면 과정을 가리지 않고, {@code status} 가 null 이면
     * 상태를 가리지 않는다. {@code keyword} 는 신청자 이름·소속 부분일치다.
     */
    PageSlice<Enrollment> search(UUID courseId, EnrollmentStatus status, String keyword, PageSpec page);

    /**
     * 그 과정에서 그 상태인 신청 수.
     *
     * <p>정원 검사는 이 수 하나에 달려 있으므로 목록을 받아 세지 않는다 — 페이지 크기만큼만
     * 세게 되면 정원이 페이지 크기로 둔갑한다.
     */
    long countByStatus(UUID courseId, EnrollmentStatus status);
}
