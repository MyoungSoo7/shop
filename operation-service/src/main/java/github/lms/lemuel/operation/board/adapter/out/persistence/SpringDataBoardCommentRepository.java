package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SpringDataBoardCommentRepository extends JpaRepository<BoardCommentJpaEntity, Long> {

    List<BoardCommentJpaEntity> findAllByPostIdOrderByIdAsc(Long postId);

    /**
     * 글별 댓글 수를 한 번에 센다.
     *
     * <p>파생 질의로는 GROUP BY 를 만들 수 없어 JPQL 로 쓴다. 상태는 파라미터로 넘긴다 —
     * 문자열 리터럴로 박으면 enum 이름이 바뀔 때 컴파일러가 잡아 주지 않는다.
     */
    @Query("""
            SELECT c.postId, count(c) FROM BoardCommentJpaEntity c
             WHERE c.postId IN :postIds AND c.status = :status
             GROUP BY c.postId
            """)
    List<Object[]> countByPostIdIn(@Param("postIds") Collection<Long> postIds,
                                   @Param("status") BoardCommentStatus status);
}
