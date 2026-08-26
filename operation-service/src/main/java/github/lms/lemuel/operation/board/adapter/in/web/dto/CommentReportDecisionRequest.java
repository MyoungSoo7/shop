package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 신고 판정 요청.
 *
 * <p>원본(dentis)은 처리 여부 한 칸(`process_yn`)만 받아서, 큐가 닫힌 뒤에는 "내렸는지 유지했는지"를
 * 아무도 알 수 없었다. 여기서는 결과를 명시적으로 받는다 — {@code HIDDEN} 이면 댓글도 함께 내려간다.
 */
public record CommentReportDecisionRequest(
        @NotNull(message = "처리 결과는 필수입니다.") CommentReportStatus decision) {
}
