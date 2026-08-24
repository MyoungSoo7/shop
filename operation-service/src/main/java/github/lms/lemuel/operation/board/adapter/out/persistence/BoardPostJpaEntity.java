package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;
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
@Table(name = "board_posts", schema = "board")
public class BoardPostJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(name = "category_code", length = 40)
    private String categoryCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_format", nullable = false, length = 10)
    private BoardContentFormat contentFormat;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    /** 작성 시점 마스킹 스냅샷. 원문 이메일은 이 서비스에 들어오지 않는다(BoardAuthor javadoc). */
    @Column(name = "author_name", nullable = false, length = 40)
    private String authorName;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "secret", nullable = false)
    private boolean secret;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private BoardPostStatus status;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BoardPostJpaEntity() {
    }

    public static BoardPostJpaEntity from(BoardPost post) {
        BoardPostJpaEntity entity = new BoardPostJpaEntity();
        entity.id = post.getId();
        entity.apply(post);
        return entity;
    }

    public void apply(BoardPost post) {
        this.boardId = post.getBoardId();
        this.categoryCode = post.getCategoryCode();
        this.title = post.getTitle();
        this.content = post.getContent();
        this.contentFormat = post.getContentFormat();
        this.authorId = post.getAuthor().userId();
        this.authorName = post.getAuthor().displayName();
        this.pinned = post.isPinned();
        this.secret = post.isSecret();
        this.status = post.getStatus();
        this.viewCount = post.getViewCount();
        this.createdAt = post.getCreatedAt();
        this.updatedAt = post.getUpdatedAt();
    }

    public BoardPost toDomain() {
        return BoardPost.rehydrate(id, boardId, categoryCode, title, content, contentFormat,
                new BoardAuthor(authorId, authorName), pinned, secret, status, viewCount, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }
}
