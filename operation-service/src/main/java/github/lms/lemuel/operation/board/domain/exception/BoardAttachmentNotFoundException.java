package github.lms.lemuel.operation.board.domain.exception;

public class BoardAttachmentNotFoundException extends RuntimeException {

    public BoardAttachmentNotFoundException(String message) {
        super(message);
    }

    public static BoardAttachmentNotFoundException byId(Long id) {
        return new BoardAttachmentNotFoundException("첨부를 찾을 수 없습니다: id=" + id);
    }
}
