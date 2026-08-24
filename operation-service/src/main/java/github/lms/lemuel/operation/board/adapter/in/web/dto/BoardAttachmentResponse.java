package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardAttachmentKind;

import java.time.OffsetDateTime;

/**
 * 첨부 응답.
 *
 * <p>{@code storagePath} 는 내보내지 않는다 — 내부 저장 구조는 클라이언트가 알 필요가 없고,
 * 알게 되는 순간 그 경로를 직접 요청해 보려는 시도가 생긴다. 다운로드는 언제나 식별자 경유다.
 */
public record BoardAttachmentResponse(
        Long id,
        Long postId,
        BoardAttachmentKind kind,
        String originalName,
        String contentType,
        long sizeBytes,
        int sortOrder,
        String downloadUrl,
        OffsetDateTime createdAt) {

    public static BoardAttachmentResponse from(BoardAttachment attachment, String boardKey) {
        return new BoardAttachmentResponse(
                attachment.getId(),
                attachment.getPostId(),
                attachment.getKind(),
                attachment.getOriginalName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getSortOrder(),
                "/api/boards/" + boardKey + "/attachments/" + attachment.getId() + "/download",
                attachment.getCreatedAt());
    }
}
