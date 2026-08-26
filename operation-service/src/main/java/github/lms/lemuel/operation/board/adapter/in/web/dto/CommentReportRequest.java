package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.CommentReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 접수 요청.
 *
 * <p>신고자 식별자는 <b>받지 않는다</b>. 본문에서 읽으면 남의 이름으로 신고를 넣을 수 있고,
 * 그건 큐를 무기로 만드는 가장 짧은 경로다 — 주체는 JWT 에서만 온다.
 */
public record CommentReportRequest(
        @NotNull(message = "신고 사유는 필수입니다.") CommentReportReason reason,
        /** ETC 사유는 도메인이 설명을 요구한다. 길이 상한은 도메인과 같은 500 자. */
        @Size(max = 500, message = "신고 설명은 500자를 넘을 수 없습니다.") String detail) {
}
