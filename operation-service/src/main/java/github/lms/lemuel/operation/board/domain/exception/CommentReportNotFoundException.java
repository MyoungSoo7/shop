package github.lms.lemuel.operation.board.domain.exception;

public class CommentReportNotFoundException extends RuntimeException {

    public CommentReportNotFoundException(String message) {
        super(message);
    }

    public static CommentReportNotFoundException byId(Long id) {
        return new CommentReportNotFoundException("신고를 찾을 수 없습니다: id=" + id);
    }
}
