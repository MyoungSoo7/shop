package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SpringDataCommentReportRepository extends JpaRepository<CommentReportJpaEntity, Long> {

    Page<CommentReportJpaEntity> findAllByStatus(CommentReportStatus status, Pageable pageable);

    List<CommentReportJpaEntity> findAllByCommentIdOrderByIdAsc(Long commentId);

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    /**
     * 댓글별 신고 건수. 파생 질의로는 GROUP BY 를 만들 수 없어 JPQL 로 쓴다.
     *
     * <p>상태로 거르지 않는다 — 이미 판정된 신고도 "몇 명이 문제 삼았는가"에는 포함돼야 한다.
     */
    @Query("""
            SELECT r.commentId, count(r) FROM CommentReportJpaEntity r
             WHERE r.commentId IN :commentIds
             GROUP BY r.commentId
            """)
    List<Object[]> countByCommentIdIn(@Param("commentIds") Collection<Long> commentIds);
}
