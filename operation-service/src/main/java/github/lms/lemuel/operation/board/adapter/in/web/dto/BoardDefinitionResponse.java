package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 게시판 정의 응답 — 프론트의 단일 라우트가 이 값을 읽어 화면을 그린다.
 *
 * <p>{@code path} 를 계산해서 내려 주는 이유: 메뉴 등록 다이얼로그가 이 값을 그대로
 * {@code menus.path} 에 넣는다. 프론트가 {@code '/boards/' + key} 를 다시 조립하면 경로 규칙이
 * 두 곳에 생기고, 나중에 규칙이 바뀔 때 한쪽만 고쳐진다.
 */
public record BoardDefinitionResponse(
        Long id,
        String boardKey,
        String name,
        String description,
        BoardSkin skin,
        String path,
        ContentPayload content,
        AttachmentPayload attachment,
        AccessPayload access,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record ContentPayload(
            BoardContentFormat contentFormat,
            boolean commentsEnabled,
            boolean secretEnabled,
            String categoryGroupCode) {
    }

    public record AttachmentPayload(
            boolean enabled,
            int maxCount,
            int maxSizeKb,
            List<String> allowedExtensions) {
    }

    public record AccessPayload(
            List<String> readRoles,
            List<String> writeRoles,
            List<String> commentRoles,
            List<String> manageRoles,
            boolean publicRead) {
    }

    public static BoardDefinitionResponse from(BoardDefinition definition) {
        return new BoardDefinitionResponse(
                definition.getId(),
                definition.getBoardKey(),
                definition.getName(),
                definition.getDescription(),
                definition.getSkin(),
                definition.path(),
                new ContentPayload(
                        definition.getContentPolicy().contentFormat(),
                        definition.getContentPolicy().isCommentsEnabled(),
                        definition.getContentPolicy().isSecretEnabled(),
                        definition.getContentPolicy().categoryGroupCode()),
                new AttachmentPayload(
                        definition.getAttachmentPolicy().isEnabled(),
                        definition.getAttachmentPolicy().maxCount(),
                        definition.getAttachmentPolicy().maxSizeKb(),
                        List.copyOf(definition.getAttachmentPolicy().allowedExtensions())),
                new AccessPayload(
                        List.copyOf(definition.getAccessPolicy().readRoles()),
                        List.copyOf(definition.getAccessPolicy().writeRoles()),
                        List.copyOf(definition.getAccessPolicy().commentRoles()),
                        List.copyOf(definition.getAccessPolicy().manageRoles()),
                        definition.getAccessPolicy().isPublicRead()),
                definition.isActive(),
                definition.getCreatedAt(),
                definition.getUpdatedAt());
    }
}
