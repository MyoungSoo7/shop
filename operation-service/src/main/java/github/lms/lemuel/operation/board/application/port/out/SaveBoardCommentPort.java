package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardComment;

public interface SaveBoardCommentPort {

    BoardComment save(BoardComment comment);
}
