package github.lms.lemuel.operation.board.domain.exception;

public class BoardCommentNotFoundException extends RuntimeException {

    public BoardCommentNotFoundException(String message) {
        super(message);
    }

    public static BoardCommentNotFoundException byId(Long id) {
        return new BoardCommentNotFoundException("댓글을 찾을 수 없습니다: id=" + id);
    }
}
