package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 게시글.
 *
 * <p>이 애그리거트는 <b>인가를 스스로 판정한다</b>. 소유권 대조를 컨트롤러에 두면 어댑터를 하나
 * 더 만들 때(관리 콘솔·배치·내부 API) 조용히 빠지고, 그 순간 남의 글을 고칠 수 있는 경로가 생긴다.
 * 그래서 {@code edit}·{@code softDelete}·{@code changePinned} 는 모두 {@link BoardActor} 를 받는다.
 *
 * <p>본문 형식은 <b>작성 시점 스냅샷</b>이다. 게시판 정책이 나중에 TEXT→HTML 로 바뀌어도 이미 쓴
 * 글의 렌더 방식은 그대로여야 한다 — 평문으로 쓴 글이 어느 날 갑자기 마크업으로 해석되면
 * 깨져 보이거나, 더 나쁘게는 실행된다.
 */
public class BoardPost {

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int CONTENT_MAX_LENGTH = 50_000;

    private Long id;
    private Long boardId;
    private String categoryCode;
    private String title;
    private String content;
    private BoardContentFormat contentFormat;
    private BoardAuthor author;
    private boolean pinned;
    private boolean secret;
    private BoardPostStatus status;
    private long viewCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private BoardPost() {
    }

    public static BoardPost create(BoardDefinition definition, BoardActor actor, BoardAuthor author,
                                   String title, String content, String categoryCode, boolean secret,
                                   OffsetDateTime now) {
        if (!definition.isActive()) {
            throw new BoardInvariantViolationException("닫힌 게시판에는 글을 쓸 수 없습니다.");
        }
        if (!actor.isAuthenticated() || !definition.canWrite(actor.role())) {
            throw new BoardAccessDeniedException("이 게시판에 글을 쓸 권한이 없습니다.");
        }
        if (author == null || !actor.owns(author.userId())) {
            // 주체와 작성자가 갈라지는 순간 "누가 썼는가"가 요청 데이터가 된다 — 내부 호출에서도 막는다.
            throw new BoardAccessDeniedException("작성자는 요청 주체와 같아야 합니다.");
        }
        String normalizedTitle = normalizeTitle(title);
        String normalizedContent = normalizeContent(content);
        String normalizedCategory = normalizeCategory(definition, categoryCode);
        assertSecretAllowed(definition, secret);

        BoardPost post = new BoardPost();
        post.boardId = definition.getId();
        post.categoryCode = normalizedCategory;
        post.title = normalizedTitle;
        post.content = normalizedContent;
        post.contentFormat = definition.getContentPolicy().contentFormat();
        post.author = author;
        post.pinned = false;
        post.secret = secret;
        post.status = BoardPostStatus.PUBLISHED;
        post.viewCount = 0L;
        post.createdAt = now;
        post.updatedAt = now;
        return post;
    }

    public static BoardPost rehydrate(Long id, Long boardId, String categoryCode, String title, String content,
                                      BoardContentFormat contentFormat, BoardAuthor author, boolean pinned,
                                      boolean secret, BoardPostStatus status, long viewCount,
                                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        BoardPost post = new BoardPost();
        post.id = id;
        post.boardId = boardId;
        post.categoryCode = categoryCode;
        post.title = title;
        post.content = content;
        post.contentFormat = contentFormat;
        post.author = author;
        post.pinned = pinned;
        post.secret = secret;
        post.status = status;
        post.viewCount = viewCount;
        post.createdAt = createdAt;
        post.updatedAt = updatedAt;
        return post;
    }

    public void edit(BoardActor actor, BoardDefinition definition, String title, String content,
                     String categoryCode, boolean secret, OffsetDateTime now) {
        assertNotDeleted("수정");
        assertCanModify(actor, definition);
        // 검증을 끝내고 대입한다 — 중간에 던지면 제목만 바뀐 글이 남는다.
        String normalizedTitle = normalizeTitle(title);
        String normalizedContent = normalizeContent(content);
        String normalizedCategory = normalizeCategory(definition, categoryCode);
        assertSecretAllowed(definition, secret);

        this.title = normalizedTitle;
        this.content = normalizedContent;
        this.categoryCode = normalizedCategory;
        this.secret = secret;
        this.updatedAt = now;
    }

    public void softDelete(BoardActor actor, BoardDefinition definition, OffsetDateTime now) {
        assertNotDeleted("삭제");
        assertCanModify(actor, definition);
        this.status = BoardPostStatus.DELETED;
        this.updatedAt = now;
    }

    public void changePinned(BoardActor actor, BoardDefinition definition, boolean pinned, OffsetDateTime now) {
        assertNotDeleted("고정 변경");
        assertCanManage(actor, definition, "글을 고정");
        this.pinned = pinned;
        this.updatedAt = now;
    }

    public void hide(BoardActor actor, BoardDefinition definition, OffsetDateTime now) {
        assertNotDeleted("숨김");
        assertCanManage(actor, definition, "글을 숨길");
        this.status = BoardPostStatus.HIDDEN;
        this.updatedAt = now;
    }

    public void restore(BoardActor actor, BoardDefinition definition, OffsetDateTime now) {
        assertNotDeleted("복구");
        assertCanManage(actor, definition, "글을 되돌릴");
        this.status = BoardPostStatus.PUBLISHED;
        this.updatedAt = now;
    }

    /**
     * 이 업로드를 이 글에 붙일 수 있는지 — <b>바이트를 디스크에 쓰기 전에</b> 전부 판정한다.
     *
     * <p>저장한 뒤에 거절하면 거절당한 파일이 디스크에 남고, 그 정리를 잊는 순간 아무도 참조하지
     * 않는 바이너리가 쌓인다. 그래서 이 검사는 반환값이 아니라 예외로 흐름을 끊는다.
     *
     * @param existingCount 이미 붙어 있는 첨부 수 — 개수 한도는 애그리거트 밖(저장소)의 사실이라 받아 온다
     */
    public void assertCanAttach(BoardActor actor, BoardDefinition definition, AttachmentUpload upload,
                                int existingCount) {
        assertNotDeleted("첨부");
        assertCanModify(actor, definition);
        upload.validateAgainst(definition.getAttachmentPolicy());
        if (existingCount + 1 > definition.getAttachmentPolicy().maxCount()) {
            throw new BoardInvariantViolationException(
                    "첨부는 최대 " + definition.getAttachmentPolicy().maxCount() + "개까지입니다.");
        }
    }

    /**
     * 수정·삭제 권한 — 작성자 본인이거나 운영 역할.
     *
     * <p>작성자 판정은 {@link BoardActor#owns(Long)} 이 한다. 미인증({@code userId == null})이
     * 작성자 없는 글과 우연히 일치하는 일이 없도록 그쪽에서 null 을 먼저 걸러 낸다.
     */
    private void assertCanModify(BoardActor actor, BoardDefinition definition) {
        if (actor.owns(author.userId()) || definition.canManage(actor.role())) {
            return;
        }
        throw new BoardAccessDeniedException("이 글을 수정·삭제할 권한이 없습니다.");
    }

    private void assertCanManage(BoardActor actor, BoardDefinition definition, String what) {
        if (!definition.canManage(actor.role())) {
            throw new BoardAccessDeniedException(what + " 권한이 없습니다. 게시판 운영 역할이 필요합니다.");
        }
    }

    private void assertNotDeleted(String operation) {
        if (status == BoardPostStatus.DELETED) {
            throw new BoardInvariantViolationException("삭제된 글은 " + operation + "할 수 없습니다.");
        }
    }

    private static void assertSecretAllowed(BoardDefinition definition, boolean secret) {
        if (secret && !definition.getContentPolicy().isSecretEnabled()) {
            throw new BoardInvariantViolationException("이 게시판은 비밀글을 허용하지 않습니다.");
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BoardInvariantViolationException("제목은 필수입니다.");
        }
        String normalized = title.trim();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new BoardInvariantViolationException(
                    "제목은 " + TITLE_MAX_LENGTH + "자를 넘을 수 없습니다: " + normalized.length() + "자");
        }
        return normalized;
    }

    private static String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BoardInvariantViolationException("본문은 필수입니다.");
        }
        // 본문은 trim 하지 않는다 — 코드 블록·들여쓰기가 의미를 갖는 형식(MARKDOWN)이 있다.
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new BoardInvariantViolationException(
                    "본문은 " + CONTENT_MAX_LENGTH + "자를 넘을 수 없습니다: " + content.length() + "자");
        }
        return content;
    }

    /**
     * 이 주체에게 이 글이 보이는가.
     *
     * <p>"안 보임"은 404 로 응답한다 — 403 으로 가르면 식별자를 훑어 비밀글의 존재를 알아낼 수 있다.
     */
    public boolean isVisibleTo(BoardActor actor, BoardDefinition definition) {
        if (status == BoardPostStatus.DELETED) {
            return false;
        }
        if (!definition.canRead(actor.role())) {
            return false;
        }
        boolean canManage = definition.canManage(actor.role());
        if (status == BoardPostStatus.HIDDEN) {
            // 숨김은 운영 판단이라 작성자에게도 감춘다 — 작성자에게 보이면 "왜 남에게만 안 보이지"
            // 라는 문의만 늘고, 조치가 전달되지 않는다.
            return canManage;
        }
        return !secret || canManage || actor.owns(author.userId());
    }

    public void increaseView() {
        this.viewCount++;
    }

    private static String normalizeCategory(BoardDefinition definition, String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return null;
        }
        if (!definition.getContentPolicy().hasCategoryGroup()) {
            // 분류 그룹이 없는 게시판에 분류가 붙으면 그 값은 어디서도 라벨을 찾을 수 없다 —
            // 화면에는 코드값이 그대로 노출되고, 아무도 그게 오타인지 알 수 없다.
            throw new BoardInvariantViolationException("이 게시판은 분류를 쓰지 않습니다: " + categoryCode);
        }
        return categoryCode.trim().toUpperCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public Long getBoardId() {
        return boardId;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public BoardContentFormat getContentFormat() {
        return contentFormat;
    }

    public BoardAuthor getAuthor() {
        return author;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isSecret() {
        return secret;
    }

    public BoardPostStatus getStatus() {
        return status;
    }

    public long getViewCount() {
        return viewCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
