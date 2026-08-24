package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardPost;

/**
 * 게시글 쓰기 유스케이스.
 *
 * <p>주체({@link BoardActor})와 작성자 표시명({@link BoardAuthor})은 <b>웹 어댑터가 JWT 에서만</b>
 * 만들어 넘긴다. 커맨드에 작성자 식별자를 담지 않는 것은 실수가 아니다 — 담는 순간 요청 본문으로
 * 남의 이름을 달 수 있다.
 */
public interface ManagePostUseCase {

    BoardPost create(String boardKey, BoardActor actor, BoardAuthor author, PostContentCommand command);

    BoardPost edit(String boardKey, Long postId, BoardActor actor, PostContentCommand command);

    void delete(String boardKey, Long postId, BoardActor actor);

    BoardPost changePinned(String boardKey, Long postId, BoardActor actor, boolean pinned);

    BoardPost hide(String boardKey, Long postId, BoardActor actor);

    BoardPost restore(String boardKey, Long postId, BoardActor actor);

    record PostContentCommand(String title, String content, String categoryCode, boolean secret) {
    }
}
