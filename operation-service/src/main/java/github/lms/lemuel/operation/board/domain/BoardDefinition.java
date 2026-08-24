package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 게시판 정의 — 게시판 그 자체이자, 그 위에 쌓일 모든 게시글의 규칙을 담는 그릇.
 *
 * <p>이 애그리거트가 이 서비스의 전부다. {@code board_definitions} 1행 = 게시판 1개이고,
 * 프론트의 단일 라우트 {@code /boards/:boardKey} 가 이 정의를 읽어 스킨을 바꿔 그린다.
 * 그래서 게시판을 추가하는 데 배포가 필요 없다.
 */
public class BoardDefinition {

    private Long id;
    private String boardKey;
    private String name;
    private String description;
    private BoardSkin skin;
    private BoardContentPolicy contentPolicy;
    private BoardAttachmentPolicy attachmentPolicy;
    private BoardAccessPolicy accessPolicy;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** URL 세그먼트로 그대로 쓰이는 값이라 2~40자 소문자·숫자·하이픈만. 하이픈으로 시작·끝날 수 없다. */
    private static final Pattern BOARD_KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,38}[a-z0-9]");

    private static final int NAME_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 300;

    private BoardDefinition() {
    }

    public static BoardDefinition create(String boardKey, String name, String description, BoardSkin skin,
                                         BoardContentPolicy contentPolicy,
                                         BoardAttachmentPolicy attachmentPolicy,
                                         BoardAccessPolicy accessPolicy,
                                         OffsetDateTime now) {
        String normalizedKey = normalizeKey(boardKey);
        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        validate(skin, contentPolicy, attachmentPolicy, accessPolicy, now);

        BoardDefinition definition = new BoardDefinition();
        definition.boardKey = normalizedKey;
        definition.name = normalizedName;
        definition.description = normalizedDescription;
        definition.skin = skin;
        definition.contentPolicy = contentPolicy;
        definition.attachmentPolicy = attachmentPolicy;
        definition.accessPolicy = accessPolicy;
        definition.active = true;
        definition.createdAt = now;
        definition.updatedAt = now;
        return definition;
    }

    /**
     * 영속 레코드 복원 — no-arg + setter 대신 이 경로로만 도메인을 재구성한다.
     */
    public static BoardDefinition rehydrate(Long id, String boardKey, String name, String description,
                                            BoardSkin skin, BoardContentPolicy contentPolicy,
                                            BoardAttachmentPolicy attachmentPolicy,
                                            BoardAccessPolicy accessPolicy, boolean active,
                                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        BoardDefinition definition = new BoardDefinition();
        definition.id = id;
        definition.boardKey = boardKey;
        definition.name = name;
        definition.description = description;
        definition.skin = skin;
        definition.contentPolicy = contentPolicy;
        definition.attachmentPolicy = attachmentPolicy;
        definition.accessPolicy = accessPolicy;
        definition.active = active;
        definition.createdAt = createdAt;
        definition.updatedAt = updatedAt;
        return definition;
    }

    /**
     * 표시·정책 갱신. {@code boardKey} 는 바꾸지 않는다 — URL 과 메뉴 행이 그 값을 가리키고 있어
     * 바꾸는 순간 이미 나간 링크가 전부 죽는다. 키를 바꾸고 싶으면 새 게시판을 만드는 게 맞다.
     */
    public void update(String name, String description, BoardSkin skin, BoardContentPolicy contentPolicy,
                       BoardAttachmentPolicy attachmentPolicy, BoardAccessPolicy accessPolicy,
                       OffsetDateTime now) {
        // 검증을 먼저 끝내고 대입한다 — 중간에 던지면 애그리거트가 반쯤 바뀐 채 살아남는다.
        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        validate(skin, contentPolicy, attachmentPolicy, accessPolicy, now);

        this.name = normalizedName;
        this.description = normalizedDescription;
        this.skin = skin;
        this.contentPolicy = contentPolicy;
        this.attachmentPolicy = attachmentPolicy;
        this.accessPolicy = accessPolicy;
        this.updatedAt = now;
    }

    /**
     * 게시판을 닫는다. 삭제가 아니라 비활성이다 — 글이 남아 있는 게시판을 물리 삭제하면
     * 이미 배포된 링크가 404 가 되고 되돌릴 수 없다.
     */
    public void deactivate(OffsetDateTime now) {
        if (!this.active) {
            throw new BoardInvariantViolationException("이미 비활성 상태인 게시판입니다: " + boardKey);
        }
        this.active = false;
        this.updatedAt = now;
    }

    public void activate(OffsetDateTime now) {
        if (this.active) {
            throw new BoardInvariantViolationException("이미 활성 상태인 게시판입니다: " + boardKey);
        }
        this.active = true;
        this.updatedAt = now;
    }

    private static String normalizeKey(String boardKey) {
        if (boardKey == null || boardKey.isBlank()) {
            throw new BoardInvariantViolationException("게시판 키는 필수입니다.");
        }
        String normalized = boardKey.trim().toLowerCase(Locale.ROOT);
        if (!BOARD_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BoardInvariantViolationException(
                    "게시판 키는 2~40자의 소문자·숫자·하이픈만 쓸 수 있고 하이픈으로 시작·끝날 수 없습니다: " + boardKey);
        }
        return normalized;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BoardInvariantViolationException("게시판명은 필수입니다.");
        }
        String normalized = name.trim();
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new BoardInvariantViolationException(
                    "게시판명은 " + NAME_MAX_LENGTH + "자를 넘을 수 없습니다: " + normalized.length() + "자");
        }
        return normalized;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalized = description.trim();
        if (normalized.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BoardInvariantViolationException(
                    "설명은 " + DESCRIPTION_MAX_LENGTH + "자를 넘을 수 없습니다: " + normalized.length() + "자");
        }
        return normalized;
    }

    /**
     * 스킨과 정책의 정합 검사 — 이 애그리거트의 존재 이유.
     *
     * <p>스킨은 "어떻게 보이는가"이고 정책은 "무엇을 담을 수 있는가"인데, 둘이 어긋나면
     * 렌더링은 되지만 아무것도 담기지 않는 게시판이 만들어진다. 그런 게시판은 만들어진 뒤에야
     * 발견되고, 그때는 이미 메뉴에 붙어 사용자가 클릭하고 있다.
     */
    private static void validate(BoardSkin skin, BoardContentPolicy contentPolicy,
                                 BoardAttachmentPolicy attachmentPolicy, BoardAccessPolicy accessPolicy,
                                 OffsetDateTime now) {
        if (skin == null) {
            throw new BoardInvariantViolationException("스킨은 필수입니다.");
        }
        if (contentPolicy == null) {
            throw new BoardInvariantViolationException("본문 정책은 필수입니다.");
        }
        if (attachmentPolicy == null) {
            throw new BoardInvariantViolationException("첨부 정책은 필수입니다.");
        }
        if (accessPolicy == null) {
            throw new BoardInvariantViolationException("접근 정책은 필수입니다.");
        }
        if (now == null) {
            throw new BoardInvariantViolationException("기준 시각은 필수입니다.");
        }
        if (skin.requiresAttachments() && !attachmentPolicy.isEnabled()) {
            throw new BoardInvariantViolationException(
                    skin + " 스킨은 첨부를 켜야 합니다 — 썸네일 없는 그리드는 빈 칸만 남습니다.");
        }
        if (skin.requiresComments() && !contentPolicy.isCommentsEnabled()) {
            throw new BoardInvariantViolationException(
                    skin + " 스킨은 댓글을 켜야 합니다 — 답할 수단 없는 질문 게시판이 됩니다.");
        }
    }

    /** 프론트 라우트·메뉴 경로. 메뉴 연결은 이 값을 그대로 {@code menus.path} 에 넣는다. */
    public String path() {
        return "/boards/" + boardKey;
    }

    // ── 인가 판정 — 비활성 게시판은 운영자 외 아무도 접근하지 못한다 ──────────────

    public boolean canRead(String role) {
        return accessPolicy.canRead(role);
    }

    public boolean canWrite(String role) {
        return accessPolicy.canWrite(role);
    }

    public boolean canComment(String role) {
        return contentPolicy.isCommentsEnabled() && accessPolicy.canComment(role);
    }

    public boolean canManage(String role) {
        return accessPolicy.canManage(role);
    }

    public Long getId() {
        return id;
    }

    public String getBoardKey() {
        return boardKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BoardSkin getSkin() {
        return skin;
    }

    public BoardContentPolicy getContentPolicy() {
        return contentPolicy;
    }

    public BoardAttachmentPolicy getAttachmentPolicy() {
        return attachmentPolicy;
    }

    public BoardAccessPolicy getAccessPolicy() {
        return accessPolicy;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
