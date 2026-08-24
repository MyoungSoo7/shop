package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.time.OffsetDateTime;

/**
 * 댓글.
 *
 * <p>게시글과 같은 원칙이다 — 인가를 스스로 판정하고, 삭제는 상태 전이다.
 *
 * <p>깊이를 1단(댓글 → 답글)으로 제한한다. 무한 중첩은 화면에서 읽을 수 없게 되고, 조회 쿼리가
 * 재귀로 번진다. 제한이 필요하면 데이터가 쌓이기 전에 걸어야 한다.
 */
public class BoardComment {

    private static final int CONTENT_MAX_LENGTH = 2_000;
    private static final String DELETED_PLACEHOLDER = "삭제된 댓글입니다.";

    private Long id;
    private Long postId;
    private Long boardId;
    private Long parentId;
    private BoardAuthor author;
    private String content;
    private BoardCommentStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private BoardComment() {
    }

    public static BoardComment create(BoardDefinition definition, BoardPost post, BoardActor actor,
                                      BoardAuthor author, String content, BoardComment parent,
                                      OffsetDateTime now) {
        // canComment 는 역할 이전에 "이 게시판이 댓글을 받는가"를 먼저 본다.
        if (!actor.isAuthenticated() || !definition.canComment(actor.role())) {
            throw new BoardAccessDeniedException("이 게시판에 댓글을 쓸 권한이 없습니다.");
        }
        if (author == null || !actor.owns(author.userId())) {
            throw new BoardAccessDeniedException("작성자는 요청 주체와 같아야 합니다.");
        }
        if (post == null || !post.getStatus().isReadable()) {
            throw new BoardInvariantViolationException("노출 상태가 아닌 글에는 댓글을 달 수 없습니다.");
        }
        String normalized = normalizeContent(content);
        Long parentId = resolveParentId(post, parent);

        BoardComment comment = new BoardComment();
        comment.postId = post.getId();
        comment.boardId = post.getBoardId();
        comment.parentId = parentId;
        comment.author = author;
        comment.content = normalized;
        comment.status = BoardCommentStatus.PUBLISHED;
        comment.createdAt = now;
        comment.updatedAt = now;
        return comment;
    }

    public static BoardComment rehydrate(Long id, Long postId, Long boardId, Long parentId, BoardAuthor author,
                                         String content, BoardCommentStatus status,
                                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        BoardComment comment = new BoardComment();
        comment.id = id;
        comment.postId = postId;
        comment.boardId = boardId;
        comment.parentId = parentId;
        comment.author = author;
        comment.content = content;
        comment.status = status;
        comment.createdAt = createdAt;
        comment.updatedAt = updatedAt;
        return comment;
    }

    public void softDelete(BoardActor actor, BoardDefinition definition, OffsetDateTime now) {
        if (status == BoardCommentStatus.DELETED) {
            throw new BoardInvariantViolationException("이미 삭제된 댓글입니다.");
        }
        if (!actor.owns(author.userId()) && !definition.canManage(actor.role())) {
            throw new BoardAccessDeniedException("이 댓글을 삭제할 권한이 없습니다.");
        }
        this.status = BoardCommentStatus.DELETED;
        this.updatedAt = now;
    }

    /**
     * 화면에 내보낼 내용. 삭제된 댓글은 원문 대신 자리표시를 돌려준다.
     *
     * <p>원문을 지우지 않고 남기는 이유는 신고·감사 대응이다 — "무엇이 지워졌는가"에 답할 수
     * 없으면 삭제 기능 자체가 분쟁의 원인이 된다. 대신 원문은 이 경로로 절대 나가지 않는다.
     */
    public String visibleContent() {
        return status == BoardCommentStatus.DELETED ? DELETED_PLACEHOLDER : content;
    }

    private static Long resolveParentId(BoardPost post, BoardComment parent) {
        if (parent == null) {
            return null;
        }
        if (!post.getId().equals(parent.getPostId())) {
            throw new BoardInvariantViolationException("다른 글의 댓글에는 답글을 달 수 없습니다.");
        }
        if (parent.getStatus() == BoardCommentStatus.DELETED) {
            throw new BoardInvariantViolationException("삭제된 댓글에는 답글을 달 수 없습니다.");
        }
        if (parent.getParentId() != null) {
            throw new BoardInvariantViolationException("답글에는 다시 답글을 달 수 없습니다(1단까지).");
        }
        return parent.getId();
    }

    private static String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BoardInvariantViolationException("댓글 내용은 필수입니다.");
        }
        String normalized = content.trim();
        if (normalized.length() > CONTENT_MAX_LENGTH) {
            throw new BoardInvariantViolationException(
                    "댓글은 " + CONTENT_MAX_LENGTH + "자를 넘을 수 없습니다: " + normalized.length() + "자");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getBoardId() {
        return boardId;
    }

    public Long getParentId() {
        return parentId;
    }

    public BoardAuthor getAuthor() {
        return author;
    }

    public String getContent() {
        return content;
    }

    public BoardCommentStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
