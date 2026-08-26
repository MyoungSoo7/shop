package github.lms.lemuel.operation.education.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LecturerAssignmentRepository extends JpaRepository<LecturerAssignmentJpaEntity, UUID> {

    List<LecturerAssignmentJpaEntity> findByLecturerIdOrderByAssignedAtAsc(UUID lecturerId);

    List<LecturerAssignmentJpaEntity> findByCourseIdOrderByAssignedAtAsc(UUID courseId);

    boolean existsByCourseIdAndLecturerId(UUID courseId, UUID lecturerId);

    /**
     * 지운 행 수를 돌려준다 — 어댑터가 "없던 배정"과 "지웠다"를 구분해야 하기 때문이다.
     * 파생 delete 메서드는 void 라 그 구분을 못 한다.
     */
    @Modifying
    @Query("DELETE FROM LecturerAssignmentJpaEntity a WHERE a.courseId = :courseId AND a.lecturerId = :lecturerId")
    int deleteAssignment(@Param("courseId") UUID courseId, @Param("lecturerId") UUID lecturerId);
}
