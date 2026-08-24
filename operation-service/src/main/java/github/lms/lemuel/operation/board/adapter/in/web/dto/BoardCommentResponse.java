package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;

import java.time.OffsetDateTime;

/**
 * 댓글 응답.
 *
 * <p>내용은 {@code visibleContent()} 로만 내보낸다 — 삭제된 댓글의 원문은 감사용으로 DB 에
 * 남아 있지만 이 경로로는 절대 나가지 않는다.
 */
public record BoardCommentResponse(
        Long id,
        Long postId,
        Long parentId,
        String authorName,
        String content,
        boolean mine,
        boolean deletable,
        BoardCommentStatus status,
        OffsetDateTime createdAt) {

    public static BoardCommentResponse from(BoardComment comment, BoardActor actor, boolean canManage) {
        boolean mine = actor.owns(comment.getAuthor().userId());
        boolean alive = comment.getStatus() == BoardCommentStatus.PUBLISHED;
        return new BoardCommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getParentId(),
                comment.getAuthor().displayName(),
                comment.visibleContent(),
                mine,
                alive && (mine || canManage),
                comment.getStatus(),
                comment.getCreatedAt());
    }
}
