package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "board_comments", schema = "board")
public class BoardCommentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** 글을 거치지 않고도 "이 댓글이 어느 게시판 것인가"를 대조하기 위해 함께 든다. */
    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "author_name", nullable = false, length = 40)
    private String authorName;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private BoardCommentStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BoardCommentJpaEntity() {
    }

    public static BoardCommentJpaEntity from(BoardComment comment) {
        BoardCommentJpaEntity entity = new BoardCommentJpaEntity();
        entity.id = comment.getId();
        entity.apply(comment);
        return entity;
    }

    public void apply(BoardComment comment) {
        this.postId = comment.getPostId();
        this.boardId = comment.getBoardId();
        this.parentId = comment.getParentId();
        this.authorId = comment.getAuthor().userId();
        this.authorName = comment.getAuthor().displayName();
        this.content = comment.getContent();
        this.status = comment.getStatus();
        this.createdAt = comment.getCreatedAt();
        this.updatedAt = comment.getUpdatedAt();
    }

    public BoardComment toDomain() {
        return BoardComment.rehydrate(id, postId, boardId, parentId,
                new BoardAuthor(authorId, authorName), content, status, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }
}
