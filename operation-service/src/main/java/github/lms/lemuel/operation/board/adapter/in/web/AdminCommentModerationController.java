package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPageResponse;
import github.lms.lemuel.operation.board.adapter.in.web.dto.CommentReportDecisionRequest;
import github.lms.lemuel.operation.board.adapter.in.web.dto.CommentReportResponse;
import github.lms.lemuel.operation.board.adapter.in.web.dto.ModeratedCommentResponse;
import github.lms.lemuel.operation.board.application.port.in.CommentModerationUseCase;
import github.lms.lemuel.operation.board.application.port.out.CommentSearchCriteria;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 댓글 통합 관리 콘솔 API.
 *
 * <p>기존 댓글 API 는 모두 {@code /api/boards/{boardKey}/posts/{postId}/comments} 아래 있어
 * <b>글을 먼저 찾아야</b> 댓글에 닿았다. 문제 댓글을 내리려면 그 댓글이 어느 글에 달렸는지를
 * 관리자가 알아내야 했다는 뜻이다. 이 컨트롤러만 게시판·글을 건너뛰고 댓글을 직접 훑는다.
 *
 * <p>경로가 {@code /admin/boards/**} 라 {@code BoardSecurityConfig} 의 ADMIN 규칙이 이미 걸린다 —
 * 여기에 별도의 시큐리티 설정을 더하지 않는다(규칙이 두 곳에 있으면 한쪽만 고치게 된다).
 */
@Tag(name = "Comment Moderation", description = "전 게시판 댓글 조회·가림·신고 판정(ADMIN)")
@RestController
@RequestMapping("/admin/boards/comments")
@RequiredArgsConstructor
public class AdminCommentModerationController {

    private final CommentModerationUseCase commentModerationUseCase;

    @Operation(summary = "전 게시판 댓글 조회",
            description = "각 조건은 서로 독립이다 — 검색어 없이 상태만으로도 걸린다.")
    @GetMapping
    public ResponseEntity<BoardPageResponse<ModeratedCommentResponse>> search(
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) BoardCommentStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "false") boolean reportedOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CommentSearchCriteria criteria = new CommentSearchCriteria(
                boardId, status, normalize(keyword), authorId, reportedOnly);
        return ResponseEntity.ok(BoardPageResponse.from(
                commentModerationUseCase.search(criteria, page, size), ModeratedCommentResponse::from));
    }

    @Operation(summary = "댓글 가림", description = "되돌릴 수 있다. 삭제와 달리 원문이 그대로 남는다.")
    @PostMapping("/{commentId}/hide")
    public ResponseEntity<Void> hide(@PathVariable Long commentId) {
        commentModerationUseCase.hide(commentId, CurrentActor.resolve());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "댓글 가림 해제")
    @PostMapping("/{commentId}/unhide")
    public ResponseEntity<Void> unhide(@PathVariable Long commentId) {
        commentModerationUseCase.unhide(commentId, CurrentActor.resolve());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "댓글별 신고 내역", description = "판정 근거가 되는 사유·설명을 모아 본다.")
    @GetMapping("/{commentId}/reports")
    public ResponseEntity<List<CommentReportResponse>> reportsOf(@PathVariable Long commentId) {
        return ResponseEntity.ok(commentModerationUseCase.reportsOf(commentId).stream()
                .map(CommentReportResponse::from)
                .toList());
    }

    @Operation(summary = "신고 큐", description = "오래된 순. 상태를 안 주면 전부.")
    @GetMapping("/reports")
    public ResponseEntity<BoardPageResponse<CommentReportResponse>> queue(
            @RequestParam(required = false) CommentReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(BoardPageResponse.from(
                commentModerationUseCase.queue(status, page, size), CommentReportResponse::from));
    }

    @Operation(summary = "신고 판정",
            description = "HIDDEN 이면 대상 댓글도 같은 트랜잭션에서 내려간다. KEPT 는 유지 판정.")
    @PostMapping("/reports/{reportId}/resolve")
    public ResponseEntity<CommentReportResponse> resolve(@PathVariable Long reportId,
                                                        @Valid @RequestBody CommentReportDecisionRequest request) {
        // 처리자는 JWT 에서만 온다 — 본문으로 받으면 남의 이름으로 판정을 남길 수 있다.
        String handledBy = CurrentActor.requireAuthor().displayName();
        return ResponseEntity.ok(CommentReportResponse.from(commentModerationUseCase.resolve(
                reportId, request.decision(), CurrentActor.resolve(), handledBy)));
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
