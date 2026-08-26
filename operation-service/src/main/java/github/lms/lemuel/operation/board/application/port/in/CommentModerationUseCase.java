package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.application.port.out.CommentSearchCriteria;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;

import java.util.List;

/**
 * 댓글 통합 관리 유스케이스 — 신고 접수(이용자)와 판정·조치(운영)를 한 곳에 둔다.
 *
 * <p>둘을 나누지 않는 이유는 판정이 신고를 <b>닫으면서 동시에</b> 댓글을 내리기 때문이다.
 * 두 서비스로 갈라 두면 그 두 쓰기가 서로 다른 트랜잭션에 놓여, 신고는 처리됐는데 댓글은
 * 그대로 남는 상태가 생긴다 — dentis 에서 실제로 그랬다(거기선 아예 댓글을 건드리지 않았다).
 */
public interface CommentModerationUseCase {

    /** 이용자 신고 접수. 접수만으로는 댓글에 아무 일도 일어나지 않는다. */
    CommentReport report(String boardKey, Long commentId, BoardActor actor, BoardAuthor reporter,
                         CommentReportReason reason, String detail);

    /** 전 게시판 댓글 조회(운영). */
    BoardPage<ModeratedComment> search(CommentSearchCriteria criteria, int page, int size);

    /** 댓글 하나에 붙은 신고 전부. */
    List<CommentReport> reportsOf(Long commentId);

    /** 신고 큐. status 가 null 이면 전부. */
    BoardPage<CommentReport> queue(CommentReportStatus status, int page, int size);

    BoardComment hide(Long commentId, BoardActor actor);

    BoardComment unhide(Long commentId, BoardActor actor);

    /**
     * 신고를 판정한다. HIDDEN 판정이면 대상 댓글도 같은 트랜잭션에서 내려간다.
     *
     * @param handledBy 처리자 라벨(마스킹된 표시명). 감사에서 "누가 판정했는가"에 답한다
     */
    CommentReport resolve(Long reportId, CommentReportStatus decision, BoardActor actor, String handledBy);
}
