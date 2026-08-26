package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.CommentReport;

@FunctionalInterface
public interface SaveCommentReportPort {

    CommentReport save(CommentReport report);
}
