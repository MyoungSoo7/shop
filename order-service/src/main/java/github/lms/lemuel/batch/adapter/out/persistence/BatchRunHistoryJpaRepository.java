package github.lms.lemuel.batch.adapter.out.persistence;

import github.lms.lemuel.batch.domain.BatchRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BatchRunHistoryJpaRepository extends JpaRepository<BatchRunHistoryJpaEntity, Long> {

    /**
     * 조건 조합 조회. 세 인자 모두 {@code null} 허용 — 널이면 그 축은 안 건다.
     *
     * <p>파생 쿼리 이름으로는 "널이면 조건 제외" 를 표현할 수 없어 명시 쿼리로 둔다.
     */
    @Query("""
            SELECT h FROM BatchRunHistoryJpaEntity h
            WHERE (:batchName IS NULL OR h.batchName = :batchName)
              AND (:status IS NULL OR h.status = :status)
              AND (:targetDate IS NULL OR h.targetDate = :targetDate)
            ORDER BY h.startedAt DESC
            """)
    Page<BatchRunHistoryJpaEntity> search(@Param("batchName") String batchName,
                                          @Param("status") BatchRunStatus status,
                                          @Param("targetDate") LocalDate targetDate,
                                          Pageable pageable);

    /**
     * 배치별 최근 실행 1건씩.
     *
     * <p>"무엇이 안 돌고 있는가" 는 이 목록의 <b>부재</b>로 드러난다 — 매일 도는 배치인데
     * 마지막 성공이 사흘 전이면 그 사흘이 구멍이다.
     */
    @Query("""
            SELECT h FROM BatchRunHistoryJpaEntity h
            WHERE h.startedAt = (
                SELECT MAX(i.startedAt) FROM BatchRunHistoryJpaEntity i WHERE i.batchName = h.batchName
            )
            ORDER BY h.batchName ASC
            """)
    List<BatchRunHistoryJpaEntity> findLatestPerBatch();
}
