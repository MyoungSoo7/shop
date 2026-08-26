package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<EnrollmentJpaEntity, UUID> {

    /**
     * 과정·상태·검색어를 한 쿼리로 받는다. 셋 다 선택이라 파생 메서드로 풀면 조합마다 메서드가
     * 하나씩 생기고(2×2×2), 그중 하나만 조건을 빠뜨려도 조용히 전건이 조회된다.
     *
     * <p>키워드는 이름과 소속 둘 다에 건다 — 운영자는 "김OO" 로도 "OO치과" 로도 찾는다.
     */
    @Query("""
            SELECT e FROM EnrollmentJpaEntity e
            WHERE (:courseId IS NULL OR e.courseId = :courseId)
              AND (:status IS NULL OR e.status = :status)
              AND (:keyword = '' OR LOWER(e.applicantName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(COALESCE(e.applicantOrganization, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<EnrollmentJpaEntity> search(@Param("courseId") UUID courseId,
                                     @Param("status") EnrollmentStatus status,
                                     @Param("keyword") String keyword,
                                     Pageable pageable);

    long countByCourseIdAndStatus(UUID courseId, EnrollmentStatus status);
}
