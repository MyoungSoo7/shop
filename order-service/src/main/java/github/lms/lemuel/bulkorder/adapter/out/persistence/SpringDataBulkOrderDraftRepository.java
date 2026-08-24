package github.lms.lemuel.bulkorder.adapter.out.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataBulkOrderDraftRepository extends JpaRepository<BulkOrderDraftJpaEntity, Long> {

    /**
     * 상세 조회는 행·셀까지 한 번에 가져온다 — 행 200 개짜리 초안을 지연 로딩으로 그리면
     * 셀 조회만 200 번 나간다(N+1). 화면은 언제나 셀까지 함께 필요하다.
     */
    @EntityGraph(attributePaths = {"rows", "rows.cells"})
    @Query("SELECT d FROM BulkOrderDraftJpaEntity d WHERE d.id = :id")
    Optional<BulkOrderDraftJpaEntity> findDetailById(@Param("id") Long id);

    /** 목록은 행을 끌고 오지 않는다 — 요약만 보여 주는 화면에 수천 셀을 실을 이유가 없다. */
    List<BulkOrderDraftJpaEntity> findByUploaderUserIdOrderByUploadedAtDesc(Long uploaderUserId);
}
