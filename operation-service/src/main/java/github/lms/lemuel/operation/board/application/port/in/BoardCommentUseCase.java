package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;

import java.util.List;
import java.util.Map;

/**
 * 댓글 유스케이스.
 *
 * <p>조회와 쓰기를 한 인터페이스에 둔다 — 댓글은 글 상세 화면 하나에서만 쓰이고, 목록·작성·삭제가
 * 항상 함께 움직인다. 게시글처럼 관리 콘솔과 이용 화면으로 갈라지지 않는다.
 */
public interface BoardCommentUseCase {

    /** 글 하나의 댓글 전체. 삭제된 댓글도 자리표시로 남아 대화의 앞말이 사라지지 않는다. */
    List<BoardComment> listByPost(String boardKey, Long postId, BoardActor actor);

    /**
     * 글별 댓글 수 — QNA 목록의 "답변 대기/완료" 판정용. 한 번의 질의로 채운다.
     */
    Map<Long, Integer> countByPost(String boardKey, List<Long> postIds, BoardActor actor);

    BoardComment create(String boardKey, Long postId, BoardActor actor, BoardAuthor author,
                        String content, Long parentId);

    void delete(String boardKey, Long commentId, BoardActor actor);
}
