package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;

import java.time.OffsetDateTime;

public record CommentReportResponse(
        Long id,
        Long commentId,
        String reporterName,
        CommentReportReason reason,
        String detail,
        CommentReportStatus status,
        String handledBy,
        OffsetDateTime handledAt,
        OffsetDateTime createdAt) {

    public static CommentReportResponse from(CommentReport report) {
        return new CommentReportResponse(
                report.getId(),
                report.getCommentId(),
                report.getReporter().displayName(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getHandledBy(),
                report.getHandledAt(),
                report.getCreatedAt());
    }
}
