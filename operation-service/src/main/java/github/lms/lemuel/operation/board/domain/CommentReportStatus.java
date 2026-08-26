package github.lms.lemuel.operation.board.domain;

/**
 * 신고 처리 상태.
 *
 * <p>dentis 는 이걸 process_yn 하나로 뒀다. 그러면 "처리했다"는 사실만 남고 <b>어느 쪽으로</b>
 * 처리했는지가 사라진다 — 같은 사람이 같은 댓글로 다시 신고됐을 때 지난번에 유지 판정이었는지
 * 가림 판정이었는지 알 수 없다. 결과를 둘로 나눠 남긴다.
 */
public enum CommentReportStatus {
    /** 접수됨 — 아직 아무도 안 봤다 */
    RECEIVED,
    /** 신고를 받아들였고 댓글을 내렸다 */
    HIDDEN,
    /** 신고를 보고 그대로 두기로 했다 */
    KEPT;

    public boolean isHandled() {
        return this != RECEIVED;
    }
}
