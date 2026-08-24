package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardAttachmentKind;
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
@Table(name = "board_attachments", schema = "board")
public class BoardAttachmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private BoardAttachmentKind kind;

    /** 표시용 원본 파일명. 경로에는 절대 쓰이지 않는다. */
    @Column(name = "original_name", nullable = false, length = 200)
    private String originalName;

    @Column(name = "stored_name", nullable = false, length = 100)
    private String storedName;

    @Column(name = "storage_path", nullable = false, length = 300)
    private String storagePath;

    /** 축소본 경로. NULL 가능 — WEBP 처럼 리더가 없는 형식은 만들지 못한다. */
    @Column(name = "thumbnail_path", length = 300)
    private String thumbnailPath;

    /** 서버 판정값. 요청 헤더의 Content-Type 이 아니다. */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BoardAttachmentJpaEntity() {
    }

    public static BoardAttachmentJpaEntity from(BoardAttachment attachment) {
        BoardAttachmentJpaEntity entity = new BoardAttachmentJpaEntity();
        entity.id = attachment.getId();
        entity.postId = attachment.getPostId();
        entity.boardId = attachment.getBoardId();
        entity.kind = attachment.getKind();
        entity.originalName = attachment.getOriginalName();
        entity.storedName = attachment.getStoredName();
        entity.storagePath = attachment.getStoragePath();
        entity.thumbnailPath = attachment.getThumbnailPath();
        entity.contentType = attachment.getContentType();
        entity.sizeBytes = attachment.getSizeBytes();
        entity.sortOrder = attachment.getSortOrder();
        entity.createdAt = attachment.getCreatedAt();
        return entity;
    }

    public BoardAttachment toDomain() {
        return BoardAttachment.rehydrate(id, postId, boardId, kind, originalName, storedName,
                storagePath, thumbnailPath, contentType, sizeBytes, sortOrder, createdAt);
    }

    public Long getId() {
        return id;
    }
}
