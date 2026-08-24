package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 게시판 정의 영속 엔티티.
 *
 * <p>역할 allowlist 와 허용 확장자는 <b>쉼표 결합 문자열</b>로 저장한다. 별도 테이블로 정규화하면
 * 게시판 하나 읽는 데 조인이 4개 붙는데, 값의 개수는 한 자리이고 부분 조회(이 역할이 쓸 수 있는
 * 게시판 목록)를 SQL 로 할 일이 없다 — 판정은 항상 애그리거트를 통째로 읽어 도메인이 한다.
 *
 * <p>도메인 변환은 {@link #toDomain()} / {@link #from(BoardDefinition)} 한 쌍으로 고정한다.
 * 복원은 {@code rehydrate} 경로만 쓴다 — 저장된 값을 다시 검증하면 정책 강화 시 조회가 죽는다.
 */
@Entity
@Table(name = "board_definitions", schema = "board")
public class BoardDefinitionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_key", nullable = false, unique = true, length = 40)
    private String boardKey;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "skin", nullable = false, length = 10)
    private BoardSkin skin;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_format", nullable = false, length = 10)
    private BoardContentFormat contentFormat;

    @Column(name = "category_group_code", length = 40)
    private String categoryGroupCode;

    @Column(name = "comments_enabled", nullable = false)
    private boolean commentsEnabled;

    @Column(name = "secret_enabled", nullable = false)
    private boolean secretEnabled;

    @Column(name = "attachments_enabled", nullable = false)
    private boolean attachmentsEnabled;

    @Column(name = "max_attachment_count", nullable = false)
    private int maxAttachmentCount;

    @Column(name = "max_attachment_size_kb", nullable = false)
    private int maxAttachmentSizeKb;

    @Column(name = "allowed_extensions", length = 200)
    private String allowedExtensions;

    @Column(name = "read_roles", length = 200)
    private String readRoles;

    @Column(name = "write_roles", length = 200)
    private String writeRoles;

    @Column(name = "comment_roles", length = 200)
    private String commentRoles;

    @Column(name = "manage_roles", length = 200)
    private String manageRoles;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BoardDefinitionJpaEntity() {
    }

    public static BoardDefinitionJpaEntity from(BoardDefinition definition) {
        BoardDefinitionJpaEntity entity = new BoardDefinitionJpaEntity();
        entity.id = definition.getId();
        entity.apply(definition);
        return entity;
    }

    /**
     * 기존 행에 도메인 상태를 덮어쓴다. 키는 갱신 대상이 아니지만(도메인이 바꾸지 않는다) 신규
     * 저장 경로와 코드를 공유하기 위해 함께 대입한다.
     */
    public void apply(BoardDefinition definition) {
        this.boardKey = definition.getBoardKey();
        this.name = definition.getName();
        this.description = definition.getDescription();
        this.skin = definition.getSkin();

        BoardContentPolicy content = definition.getContentPolicy();
        this.contentFormat = content.contentFormat();
        this.categoryGroupCode = content.categoryGroupCode();
        this.commentsEnabled = content.isCommentsEnabled();
        this.secretEnabled = content.isSecretEnabled();

        BoardAttachmentPolicy attachment = definition.getAttachmentPolicy();
        this.attachmentsEnabled = attachment.isEnabled();
        this.maxAttachmentCount = attachment.maxCount();
        this.maxAttachmentSizeKb = attachment.maxSizeKb();
        this.allowedExtensions = join(attachment.allowedExtensions());

        BoardAccessPolicy access = definition.getAccessPolicy();
        this.readRoles = join(access.readRoles());
        this.writeRoles = join(access.writeRoles());
        this.commentRoles = join(access.commentRoles());
        this.manageRoles = join(access.manageRoles());

        this.active = definition.isActive();
        this.createdAt = definition.getCreatedAt();
        this.updatedAt = definition.getUpdatedAt();
    }

    public BoardDefinition toDomain() {
        return BoardDefinition.rehydrate(
                id, boardKey, name, description, skin,
                BoardContentPolicy.rehydrate(contentFormat, commentsEnabled, secretEnabled, categoryGroupCode),
                BoardAttachmentPolicy.rehydrate(attachmentsEnabled, maxAttachmentCount, maxAttachmentSizeKb,
                        split(allowedExtensions)),
                BoardAccessPolicy.rehydrate(split(readRoles), split(writeRoles),
                        split(commentRoles), split(manageRoles)),
                active, createdAt, updatedAt);
    }

    private static String join(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().collect(Collectors.joining(","));
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        // LinkedHashSet 로 접어 순서를 보존하면서 중복 저장분(수기 수정 등)을 흡수한다.
        return List.copyOf(new LinkedHashSet<>(Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList()));
    }

    public Long getId() {
        return id;
    }
}
