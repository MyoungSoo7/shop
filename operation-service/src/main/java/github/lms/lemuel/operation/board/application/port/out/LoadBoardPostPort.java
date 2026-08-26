package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.domain.BoardPost;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadBoardPostPort {

    Optional<BoardPost> findById(Long id);

    /** 고정 글이 먼저, 그다음 최신순. 이 정렬은 화면 규약이라 어댑터가 바꾸지 않는다. */
    BoardPage<BoardPost> search(PostSearchCriteria criteria, int page, int size);

    /**
     * 식별자 → 제목. 댓글 콘솔이 한 화면의 맥락을 왕복 한 번으로 채운다.
     *
     * <p>글이 없으면 그 키가 아예 빠진다 — 응용 계층이 빈 문자열로 메운다.
     */
    Map<Long, String> findTitlesByIds(List<Long> postIds);
}
