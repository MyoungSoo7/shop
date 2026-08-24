package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardComment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadBoardCommentPort {

    Optional<BoardComment> findById(Long id);

    /** 작성순. 답글은 부모 바로 아래 놓이도록 응용 계층이 다시 엮는다. */
    List<BoardComment> findByPostId(Long postId);

    /**
     * 글마다 살아 있는 댓글 수 — QNA 목록의 "답변 대기/완료" 판정에 쓴다.
     *
     * <p>글 하나씩 세면 한 화면에 20번의 왕복이 생긴다. 삭제된 댓글은 답변으로 치지 않는다.
     */
    Map<Long, Integer> countPublishedByPostIds(List<Long> postIds);
}
