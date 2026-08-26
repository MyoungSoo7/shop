package github.lms.lemuel.operation.board.domain;

public enum BoardCommentStatus {
    PUBLISHED,
    /**
     * 운영이 내렸고 되돌릴 수 있다 — 신고 처리의 결과다.
     *
     * <p>삭제와 나눠 두는 이유는 되돌릴 수 있는지에 있다. 삭제는 작성자 본인도 하고 되돌릴 수
     * 없지만, 가림은 운영 판단이라 오판이 섞인다. 복구 경로가 없으면 운영은 아예 내리지 않게 되고
     * 신고 큐는 장식이 된다.
     */
    HIDDEN,
    /** 지워졌지만 자리는 남는다 — 답글이 달린 댓글을 없애면 대화의 앞말이 사라진다 */
    DELETED;

    /** 원문을 화면에 내보내도 되는 상태인가. */
    public boolean isReadable() {
        return this == PUBLISHED;
    }
}
