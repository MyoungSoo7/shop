package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPolicyPayloads.AccessPayload;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPolicyPayloads.AttachmentPayload;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPolicyPayloads.ContentPayload;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.UpdateBoardCommand;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 게시판 키는 받지 않는다 — 키 변경은 이미 나간 링크를 전부 죽이므로 지원하지 않는다. */
public record BoardUpdateRequest(
        @NotBlank(message = "게시판명은 필수입니다.") String name,
        String description,
        @NotNull(message = "스킨은 필수입니다.") BoardSkin skin,
        @Valid @NotNull(message = "본문 정책은 필수입니다.") ContentPayload content,
        @Valid @NotNull(message = "첨부 정책은 필수입니다.") AttachmentPayload attachment,
        @Valid @NotNull(message = "접근 정책은 필수입니다.") AccessPayload access) {

    public UpdateBoardCommand toCommand() {
        return new UpdateBoardCommand(name, description, skin,
                content.toSpec(), attachment.toSpec(), access.toSpec());
    }
}
