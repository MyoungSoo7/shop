package github.lms.lemuel.operation.board.domain;

/**
 * 신고 사유.
 *
 * <p>dentis 는 사유를 공통코드 테이블(tb_common_code)에서 끌어 썼다. 여기서는 열거형으로 둔다 —
 * 사유는 다섯 개뿐이고 화면·통계·처리 기준이 전부 이 값에 붙는데, 코드 테이블에 두면 운영이
 * 임의로 늘린 사유가 어느 처리 분기에도 걸리지 않은 채 쌓인다.
 */
public enum CommentReportReason {
    /** 광고·도배 */
    SPAM,
    /** 욕설·비방 */
    ABUSE,
    /** 음란·선정 */
    ADULT,
    /** 개인정보 노출 */
    PRIVACY,
    /** 그 밖 — 이 사유는 상세 설명을 반드시 받는다 */
    ETC
}
