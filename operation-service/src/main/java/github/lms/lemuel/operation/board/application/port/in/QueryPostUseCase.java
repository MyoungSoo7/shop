package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardPost;

public interface QueryPostUseCase {

    BoardPage<BoardPost> list(String boardKey, BoardActor actor, PostListQuery query);

    /**
     * 단건 조회 + 조회수 증가.
     *
     * <p>읽을 수 없는 글은 404({@code BoardPostNotFoundException}) 다 — 403 으로 가르면
     * 식별자를 훑어 비밀글의 존재를 알아낼 수 있다.
     */
    BoardPost read(String boardKey, Long postId, BoardActor actor);

    record PostListQuery(int page, int size, String categoryCode, String keyword) {

        private static final int MAX_SIZE = 100;

        public PostListQuery {
            page = Math.max(page, 0);
            // 상한이 없으면 size=1000000 한 방으로 게시판 전체를 덤프할 수 있다.
            size = size <= 0 ? 20 : Math.min(size, MAX_SIZE);
        }
    }
}
