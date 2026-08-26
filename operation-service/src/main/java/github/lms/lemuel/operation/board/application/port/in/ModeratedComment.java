package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.domain.BoardComment;

/**
 * 통합 콘솔의 한 줄 — 댓글 + 그 댓글을 읽는 데 필요한 맥락.
 *
 * <p>댓글만 주면 화면은 "어느 게시판 어느 글에 달린 말인가"를 알 수 없고, 관리자는 판정을 못
 * 한다. 그렇다고 어댑터에서 조인 결과를 그대로 올리면 영속 타입이 응용 계층을 통과한다 —
 * 도메인 객체와 곁들이 값을 이 자리에서 묶는다.
 *
 * @param boardKey   게시판 키. 삭제·가림 요청이 이 값을 그대로 되쓴다
 * @param postTitle  글 제목. 글이 이미 지워졌으면 null 이 아니라 원래 제목이 그대로 온다
 *                   (글은 물리 삭제하지 않는다)
 * @param reportCount 접수된 신고 건수. 처리 여부와 무관하다
 */
public record ModeratedComment(
        BoardComment comment,
        String boardKey,
        String boardName,
        String postTitle,
        int reportCount) {
}
