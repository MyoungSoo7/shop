package github.lms.lemuel.operation.board.domain;

/**
 * 게시글 상태.
 *
 * <p>삭제는 물리 삭제가 아니라 {@code DELETED} 전이다 — 댓글이 달린 글을 지우면 대화의 앞말이
 * 사라지고, 신고·감사 대응에서 "무엇이 지워졌는지"를 답할 수 없게 된다.
 *
 * <p>{@code HIDDEN} 과 {@code DELETED} 를 가르는 이유: 숨김은 운영자가 되돌릴 수 있는 조치이고
 * 삭제는 작성자의 의사표시다. 하나로 합치면 "운영자가 숨긴 글"을 작성자가 되살릴 수 있게 된다.
 */
public enum BoardPostStatus {
    /** 정상 노출 */
    PUBLISHED,
    /** 운영자가 내림 — 작성자도 목록에서 볼 수 없다. 운영자는 되돌릴 수 있다 */
    HIDDEN,
    /** 작성자·운영자가 지움 — 되돌리지 않는다 */
    DELETED;

    public boolean isReadable() {
        return this == PUBLISHED;
    }
}
