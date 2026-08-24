package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.time.OffsetDateTime;

/**
 * 첨부 한 건.
 *
 * <p><b>저장 경로는 서버가 만든다.</b> 업로더가 준 이름은 표시용({@code originalName})으로만 쓰고,
 * 실제 파일은 UUID 로 새로 이름 붙인다 — 경로 조작이 성립할 자리를 아예 없앤다.
 *
 * <p>{@code contentType} 도 요청 헤더가 아니라 <b>서버 판정값</b>을 저장한다. 다운로드 응답이
 * 이 값을 그대로 쓰기 때문에, 클라이언트가 준 값을 저장하면 업로더가 응답 헤더를 정하는 셈이 된다.
 */
public class BoardAttachment {

    private Long id;
    private Long postId;
    private Long boardId;
    private BoardAttachmentKind kind;
    private String originalName;
    private String storedName;
    private String storagePath;
    private String thumbnailPath;
    private String contentType;
    private long sizeBytes;
    private int sortOrder;
    private OffsetDateTime createdAt;

    private BoardAttachment() {
    }

    /**
     * 검증을 통과한 업로드로부터 첨부를 만든다.
     *
     * <p>정책 검사는 이미 {@code BoardPost.assertCanAttach} 에서 끝났다 — 여기서 다시 하지 않는
     * 이유는 바이트가 이미 디스크에 쓰인 뒤이기 때문이다. 거절은 저장 <b>전에</b> 끝나야 한다.
     */
    public static BoardAttachment of(BoardPost post, AttachmentUpload upload,
                                     String storedName, String storagePath, String thumbnailPath,
                                     int sortOrder, OffsetDateTime now) {
        if (storedName == null || storedName.isBlank() || storagePath == null || storagePath.isBlank()) {
            throw new BoardInvariantViolationException("저장 위치는 필수입니다.");
        }
        BoardAttachment attachment = new BoardAttachment();
        attachment.postId = post.getId();
        attachment.boardId = post.getBoardId();
        attachment.kind = upload.kind();
        attachment.originalName = upload.originalName();
        attachment.storedName = storedName;
        attachment.storagePath = storagePath;
        attachment.thumbnailPath = thumbnailPath;
        attachment.contentType = upload.detectedType().contentType();
        attachment.sizeBytes = upload.sizeBytes();
        attachment.sortOrder = sortOrder;
        attachment.createdAt = now;
        return attachment;
    }

    public static BoardAttachment rehydrate(Long id, Long postId, Long boardId, BoardAttachmentKind kind,
                                            String originalName, String storedName, String storagePath,
                                            String thumbnailPath, String contentType, long sizeBytes,
                                            int sortOrder, OffsetDateTime createdAt) {
        BoardAttachment attachment = new BoardAttachment();
        attachment.id = id;
        attachment.postId = postId;
        attachment.boardId = boardId;
        attachment.kind = kind;
        attachment.originalName = originalName;
        attachment.storedName = storedName;
        attachment.storagePath = storagePath;
        attachment.thumbnailPath = thumbnailPath;
        attachment.contentType = contentType;
        attachment.sizeBytes = sizeBytes;
        attachment.sortOrder = sortOrder;
        attachment.createdAt = createdAt;
        return attachment;
    }

    /**
     * 삭제 권한 — 글을 고칠 수 있는 사람이 그 글의 첨부도 뗄 수 있다.
     *
     * <p>첨부만 따로 권한을 두지 않는 이유: 첨부는 글의 일부지 독립된 소유물이 아니다. 따로 두면
     * "글은 못 고치는데 첨부는 지울 수 있는" 상태가 생긴다.
     */
    public void assertRemovable(BoardActor actor, BoardDefinition definition, BoardPost post) {
        if (!postId.equals(post.getId())) {
            throw new BoardInvariantViolationException("이 글의 첨부가 아닙니다.");
        }
        if (actor.owns(post.getAuthor().userId()) || definition.canManage(actor.role())) {
            return;
        }
        throw new BoardAccessDeniedException("이 첨부를 삭제할 권한이 없습니다.");
    }

    public boolean isImage() {
        return kind == BoardAttachmentKind.IMAGE;
    }

    /**
     * 다운로드를 브라우저가 <b>열어도 되는가</b>.
     *
     * <p>이미지만 inline 이다. 그 밖의 형식을 inline 으로 내보내면 같은 오리진에서 문서로 해석될
     * 여지가 생긴다 — 첨부는 우리 도메인에서 서빙되므로 그 문서는 우리 쿠키를 볼 수 있다.
     */
    public boolean allowsInlineDisposition() {
        return isImage();
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

    public BoardAttachmentKind getKind() {
        return kind;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getStoredName() {
        return storedName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    /** 축소본 경로. 없을 수 있다 — WEBP 처럼 리더가 없는 형식은 축소본을 만들지 못한다. */
    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public boolean hasThumbnail() {
        return thumbnailPath != null && !thumbnailPath.isBlank();
    }

    /**
     * 목록에 내려 줄 경로 — 축소본이 있으면 축소본, 없으면 원본.
     *
     * <p>이 선택을 화면이 하게 두면 "썸네일이 없을 때 어떻게 하지"가 화면마다 갈린다.
     */
    public String displayPath() {
        return hasThumbnail() ? thumbnailPath : storagePath;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
