package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardPost;

public interface SaveBoardPostPort {

    BoardPost save(BoardPost post);
}
