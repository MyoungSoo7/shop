package github.lms.lemuel.operation.board.domain;

/**
 * 게시판 스킨 — 하나의 프론트 렌더 컴포넌트가 담당하는 표현 방식.
 *
 * <p>스킨을 코드(enum)로 봉인하는 이유는 {@code MenuArea} 와 같다: 스킨을 늘리는 일은 데이터
 * 입력이 아니라 프론트 컴포넌트를 새로 만드는 일이다. 관리 화면에서 임의 문자열을 넣어
 * <b>렌더링되지 않는 게시판</b>이 만들어지는 사고를 막아야 한다.
 *
 * <p>"CRUD 게시판"과 "이미지 게시판"은 별개 도메인이 아니라 여기의 두 값이다 — 이미지 게시판은
 * {@code GALLERY} + 첨부 허용이라는 정책 조합일 뿐이다.
 */
public enum BoardSkin {
    /** 제목 목록 + 페이징 — 공지사항·자료실 */
    LIST,
    /** 썸네일 그리드 — 이미지 게시판·포토 갤러리 */
    GALLERY,
    /** 아코디언 — FAQ */
    FAQ,
    /** 질문 + 답변 — 문의 */
    QNA;

    /**
     * 이 스킨이 대표 이미지를 전제로 하는가.
     *
     * <p>그리드는 썸네일이 없으면 빈 칸만 늘어선다 — 그래서 첨부를 끌 수 없다(정의 조립 시 검사).
     */
    public boolean requiresAttachments() {
        return this == GALLERY;
    }

    /**
     * 이 스킨이 답변(댓글)을 전제로 하는가.
     *
     * <p>QNA 는 답변이 곧 댓글이다. 댓글을 끄면 질문만 쌓이고 답할 수단이 없는 게시판이 된다.
     */
    public boolean requiresComments() {
        return this == QNA;
    }
}
