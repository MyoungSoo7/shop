package github.lms.lemuel.operation.board.domain.exception;

/**
 * 같은 사람이 같은 댓글을 다시 신고했다.
 *
 * <p>409 로 답한다. 요청 자체는 올바르고 상태가 이미 그렇게 되어 있다는 뜻이라서다 — dentis 는
 * 이걸 막지 않아 같은 신고가 큐에 몇 번이고 쌓였고, 그러면 "몇 명이 문제 삼았는가"를 셀 수 없다.
 */
public class DuplicateCommentReportException extends RuntimeException {

    public DuplicateCommentReportException(Long commentId) {
        super("이미 신고한 댓글입니다: commentId=" + commentId);
    }
}
