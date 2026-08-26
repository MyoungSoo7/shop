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
    private static final String HIDDEN_PLACEHOLDER = "신고 처리로 가려진 댓글입니다.";

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
     * 신고 처리로 내린다 — 되돌릴 수 있는 조치라 운영 역할만 한다.
     *
     * <p>작성자 본인은 못 한다. 본인이 원하는 것은 삭제이지 가림이 아니고, 본인에게 가림을 열면
     * "내가 내렸다가 잠잠해지면 올린다"는 경로가 생겨 신고 처리 이력이 뒤섞인다.
     */
    public void hide(BoardActor actor, BoardDefinition definition, OffsetDateTime now) {
        requireManager(actor, definition, "이 댓글을 가릴 권한이 없습니다.");
        if (status == BoardCommentStatus.DELETED) {
            throw new BoardInvariantViolationException("삭제된 댓글은 가릴 수 없습니다.");
        }
        if (status == BoardCommentStatus.HIDDEN) {
            throw new BoardInvariantViolationException("이미 가려진 댓글입니다.");
        }
        this.status = BoardCommentStatus.HIDDEN;
        this.updatedAt = now;
    }

    /** 가림을 되돌린다. 삭제된 댓글은 여기로 살아나지 않는다 — 삭제는 되돌리는 조치가 아니다. */
    public void unhide(BoardActor actor, BoardDefinition definition, OffsetDateTime now) {
        requireManager(actor, definition, "이 댓글을 되돌릴 권한이 없습니다.");
        if (status != BoardCommentStatus.HIDDEN) {
            throw new BoardInvariantViolationException("가려진 댓글이 아닙니다.");
        }
        this.status = BoardCommentStatus.PUBLISHED;
        this.updatedAt = now;
    }

    /**
     * 화면에 내보낼 내용. 삭제·가림 상태는 원문 대신 자리표시를 돌려준다.
     *
     * <p>원문을 지우지 않고 남기는 이유는 신고·감사 대응이다 — "무엇이 지워졌는가"에 답할 수
     * 없으면 삭제 기능 자체가 분쟁의 원인이 된다. 대신 원문은 이 경로로 절대 나가지 않는다.
     *
     * <p>관리 콘솔은 이 경로를 쓰지 않는다. 무엇을 내렸는지 못 보면 되돌릴 판단을 할 수 없어서다
     * — 그쪽은 {@link #getContent()} 로 원문을 본다.
     */
    public String visibleContent() {
        return switch (status) {
            case DELETED -> DELETED_PLACEHOLDER;
            case HIDDEN -> HIDDEN_PLACEHOLDER;
            case PUBLISHED -> content;
        };
    }

    private void requireManager(BoardActor actor, BoardDefinition definition, String message) {
        if (definition == null || !definition.canManage(actor.role())) {
            throw new BoardAccessDeniedException(message);
        }
    }

    private static Long resolveParentId(BoardPost post, BoardComment parent) {
        if (parent == null) {
            return null;
        }
        if (!post.getId().equals(parent.getPostId())) {
            throw new BoardInvariantViolationException("다른 글의 댓글에는 답글을 달 수 없습니다.");
        }
        // 가려진 댓글도 막는다 — 앞말이 안 보이는데 답글만 붙으면 대화가 읽히지 않고,
        // 신고 대상 댓글에 답글이 쌓이면 되돌릴 때 판단할 것이 늘어난다.
        if (!parent.getStatus().isReadable()) {
            throw new BoardInvariantViolationException("삭제되거나 가려진 댓글에는 답글을 달 수 없습니다.");
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
