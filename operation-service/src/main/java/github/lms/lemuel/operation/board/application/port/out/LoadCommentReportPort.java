package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadCommentReportPort {

    Optional<CommentReport> findById(Long id);

    /** 큐 화면. 미처리를 오래된 순으로 본다 — 먼저 들어온 신고가 먼저 처리돼야 한다. */
    BoardPage<CommentReport> search(CommentReportStatus status, int page, int size);

    /** 댓글 하나에 붙은 신고 전부. 판정 화면에서 사유를 모아 보여 준다. */
    List<CommentReport> findByCommentId(Long commentId);

    /**
     * 댓글별 신고 건수 — 통합 콘솔 한 화면을 왕복 한 번으로 채운다.
     *
     * <p>건수는 접수 기준이다(처리 여부와 무관). "몇 명이 문제 삼았는가"가 판정의 근거이지,
     * "몇 건이 남았는가"가 아니다.
     */
    Map<Long, Integer> countByCommentIds(List<Long> commentIds);

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);
}
