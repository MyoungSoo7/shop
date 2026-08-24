package github.lms.lemuel.operation.board.domain.exception;

/**
 * 요청한 게시판이 없다. 식별자(id) 또는 boardKey 로 찾지 못한 경우.
 */
public class BoardNotFoundException extends RuntimeException {

    public BoardNotFoundException(String message) {
        super(message);
    }

    public static BoardNotFoundException byId(Long id) {
        return new BoardNotFoundException("게시판을 찾을 수 없습니다: id=" + id);
    }

    public static BoardNotFoundException byKey(String boardKey) {
        return new BoardNotFoundException("게시판을 찾을 수 없습니다: boardKey=" + boardKey);
    }
}
