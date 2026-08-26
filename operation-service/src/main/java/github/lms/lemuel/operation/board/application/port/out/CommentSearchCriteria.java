package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardCommentStatus;

/**
 * 통합 댓글 콘솔의 조회 조건.
 *
 * <p>게시글 검색({@link PostSearchCriteria})과 달리 가시성 항목이 없다. 이 조건은 <b>관리자
 * 경로에서만</b> 쓰이고, 관리자는 내려간 댓글까지 봐야 되돌릴 판단을 할 수 있다. 가시성 판정이
 * 필요한 이용 화면은 글 하나에 매인 {@code findByPostId} 를 그대로 쓴다.
 *
 * <p>dentis 의 같은 화면은 검색 조건을 <b>중첩</b>해 두어(처리 여부 필터가 키워드 조건 안에
 * 들어 있었다) 키워드 없이 "미처리만" 을 고르면 필터가 조용히 무시됐다. 여기서는 각 항목이
 * 서로 독립이고, null 이면 그 항목만 빠진다.
 *
 * @param boardId  특정 게시판으로 좁힌다. null 이면 전 게시판
 * @param status   댓글 상태. null 이면 삭제분까지 전부
 * @param keyword  내용 부분 일치. null·공백이면 조건에서 빠진다
 * @param authorId 작성자. null 이면 전체
 * @param reportedOnly 신고가 한 건이라도 붙은 댓글만
 */
public record CommentSearchCriteria(
        Long boardId,
        BoardCommentStatus status,
        String keyword,
        Long authorId,
        boolean reportedOnly) {
}
