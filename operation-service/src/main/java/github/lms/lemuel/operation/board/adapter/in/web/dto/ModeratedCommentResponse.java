package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.application.port.in.ModeratedComment;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;

import java.time.OffsetDateTime;

/**
 * 관리 콘솔의 댓글 한 줄.
 *
 * <p>{@link BoardCommentResponse} 와 달리 <b>원문</b>을 담는다. 신고를 판정하려면 무엇이
 * 문제인지를 봐야 하는데, 자리표시("가려진 댓글입니다")만 보이면 판정할 근거가 없다. 이 경로는
 * {@code /admin/boards/**} 라 ADMIN 역할이 이미 걸려 있다.
 */
public record ModeratedCommentResponse(
        Long id,
        Long postId,
        String boardKey,
        String boardName,
        String postTitle,
        Long authorId,
        String authorName,
        String content,
        BoardCommentStatus status,
        int reportCount,
        OffsetDateTime createdAt) {

    public static ModeratedCommentResponse from(ModeratedComment row) {
        BoardComment comment = row.comment();
        return new ModeratedCommentResponse(
                comment.getId(),
                comment.getPostId(),
                row.boardKey(),
                row.boardName(),
                row.postTitle(),
                comment.getAuthor().userId(),
                comment.getAuthor().displayName(),
                comment.getContent(),
                comment.getStatus(),
                row.reportCount(),
                comment.getCreatedAt());
    }
}
