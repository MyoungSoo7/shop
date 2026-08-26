package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.time.OffsetDateTime;

/**
 * 댓글 신고.
 *
 * <p>신고는 <b>사실의 기록</b>이지 판정이 아니다. 그래서 접수 시점에는 댓글에 아무 일도 일어나지
 * 않는다 — 신고 한 건으로 글이 내려가면 신고가 곧 검열 도구가 된다. 판정은 운영이 큐에서 한다.
 *
 * <p>한 번 접수한 신고는 취소되지 않는다. 취소를 열면 "신고 → 상대가 알아챔 → 취소"로 큐가
 * 흔들리고, 무엇보다 이미 운영이 본 신고를 신고자가 지울 수 있게 된다.
 */
public class CommentReport {

    private static final int DETAIL_MAX_LENGTH = 500;

    private Long id;
    private Long commentId;
    private BoardAuthor reporter;
    private CommentReportReason reason;
    private String detail;
    private CommentReportStatus status;
    private String handledBy;
    private OffsetDateTime handledAt;
    private OffsetDateTime createdAt;

    private CommentReport() {
    }

    /**
     * 신고를 접수한다.
     *
     * @param comment 신고 대상. 자기 댓글은 신고할 수 없다 — 지우면 될 일을 큐로 보내는 경로다.
     */
    public static CommentReport receive(BoardComment comment, BoardActor actor, BoardAuthor reporter,
                                        CommentReportReason reason, String detail, OffsetDateTime now) {
        if (!actor.isAuthenticated() || reporter == null || !actor.owns(reporter.userId())) {
            throw new BoardAccessDeniedException("신고는 로그인한 본인만 할 수 있습니다.");
        }
        if (comment == null || comment.getId() == null) {
            throw new BoardInvariantViolationException("신고할 댓글이 없습니다.");
        }
        if (reporter.userId().equals(comment.getAuthor().userId())) {
            throw new BoardInvariantViolationException("자기 댓글은 신고할 수 없습니다.");
        }
        if (comment.getStatus() == BoardCommentStatus.DELETED) {
            throw new BoardInvariantViolationException("삭제된 댓글은 신고할 수 없습니다.");
        }
        if (reason == null) {
            throw new BoardInvariantViolationException("신고 사유는 필수입니다.");
        }

        CommentReport report = new CommentReport();
        report.commentId = comment.getId();
        report.reporter = reporter;
        report.reason = reason;
        report.detail = normalizeDetail(reason, detail);
        report.status = CommentReportStatus.RECEIVED;
        report.createdAt = now;
        return report;
    }

    public static CommentReport rehydrate(Long id, Long commentId, BoardAuthor reporter,
                                          CommentReportReason reason, String detail,
                                          CommentReportStatus status, String handledBy,
                                          OffsetDateTime handledAt, OffsetDateTime createdAt) {
        CommentReport report = new CommentReport();
        report.id = id;
        report.commentId = commentId;
        report.reporter = reporter;
        report.reason = reason;
        report.detail = detail;
        report.status = status;
        report.handledBy = handledBy;
        report.handledAt = handledAt;
        report.createdAt = createdAt;
        return report;
    }

    /**
     * 판정한다. 이미 처리한 신고는 다시 판정하지 않는다.
     *
     * <p>재판정을 막는 이유: 큐에서 두 사람이 같은 건을 동시에 열면 나중 사람이 앞사람의 판정을
     * 조용히 덮는다. 되돌릴 일이 있으면 댓글 쪽에서 가림을 푸는 것이 맞는 경로다 — 그쪽은
     * 무엇을 되돌렸는지가 댓글 상태로 남는다.
     */
    public void resolve(CommentReportStatus decision, String actor, OffsetDateTime now) {
        if (status.isHandled()) {
            throw new BoardInvariantViolationException("이미 처리된 신고입니다.");
        }
        if (decision == null || !decision.isHandled()) {
            throw new BoardInvariantViolationException("처리 결과는 가림 또는 유지여야 합니다.");
        }
        if (actor == null || actor.isBlank()) {
            throw new BoardInvariantViolationException("처리자를 특정할 수 없습니다.");
        }
        this.status = decision;
        this.handledBy = actor.trim();
        this.handledAt = now;
    }

    private static String normalizeDetail(CommentReportReason reason, String detail) {
        String normalized = detail == null ? null : detail.trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        // ETC 는 사유 자체가 아무것도 말해 주지 않는다. 설명이 없으면 큐에서 판정할 근거가 없다.
        if (reason == CommentReportReason.ETC && normalized == null) {
            throw new BoardInvariantViolationException("'그 밖' 사유는 설명을 함께 적어야 합니다.");
        }
        if (normalized != null && normalized.length() > DETAIL_MAX_LENGTH) {
            throw new BoardInvariantViolationException(
                    "신고 설명은 " + DETAIL_MAX_LENGTH + "자를 넘을 수 없습니다: " + normalized.length() + "자");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public Long getCommentId() {
        return commentId;
    }

    public BoardAuthor getReporter() {
        return reporter;
    }

    public CommentReportReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public CommentReportStatus getStatus() {
        return status;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public OffsetDateTime getHandledAt() {
        return handledAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
