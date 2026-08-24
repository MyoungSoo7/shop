package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.domain.BoardPost;

import java.util.Optional;

public interface LoadBoardPostPort {

    Optional<BoardPost> findById(Long id);

    /** 고정 글이 먼저, 그다음 최신순. 이 정렬은 화면 규약이라 어댑터가 바꾸지 않는다. */
    BoardPage<BoardPost> search(PostSearchCriteria criteria, int page, int size);
}
