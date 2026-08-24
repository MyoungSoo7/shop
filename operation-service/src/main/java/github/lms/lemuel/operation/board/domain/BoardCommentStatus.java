package github.lms.lemuel.operation.board.domain;

public enum BoardCommentStatus {
    PUBLISHED,
    /** 지워졌지만 자리는 남는다 — 답글이 달린 댓글을 없애면 대화의 앞말이 사라진다 */
    DELETED
}
