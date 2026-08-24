package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.AccessSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.AttachmentSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.ContentSpec;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 생성·수정 요청이 공유하는 정책 페이로드.
 *
 * <p>Bean Validation 은 <b>형식</b>만 본다(필수 여부·타입). 값의 <b>의미</b>(첨부 개수 상한,
 * 스킨과 정책의 정합, 익명 쓰기 금지)는 전부 도메인이 판정한다 — 여기서 {@code @Min} 을 붙이면
 * 같은 규칙이 두 곳에 생기고, 어댑터를 하나 더 만들 때 반드시 어긋난다.
 */
public final class BoardPolicyPayloads {

    private BoardPolicyPayloads() {
    }

    public record ContentPayload(
            @NotNull(message = "본문 형식은 필수입니다.") BoardContentFormat contentFormat,
            boolean commentsEnabled,
            boolean secretEnabled,
            String categoryGroupCode) {

        public ContentSpec toSpec() {
            return new ContentSpec(contentFormat, commentsEnabled, secretEnabled, categoryGroupCode);
        }
    }

    public record AttachmentPayload(
            boolean enabled,
            int maxCount,
            int maxSizeKb,
            List<String> allowedExtensions) {

        public AttachmentSpec toSpec() {
            return new AttachmentSpec(enabled, maxCount, maxSizeKb,
                    allowedExtensions == null ? List.of() : allowedExtensions);
        }
    }

    public record AccessPayload(
            List<String> readRoles,
            List<String> writeRoles,
            List<String> commentRoles,
            List<String> manageRoles) {

        public AccessSpec toSpec() {
            return new AccessSpec(orEmpty(readRoles), orEmpty(writeRoles),
                    orEmpty(commentRoles), orEmpty(manageRoles));
        }

        private static List<String> orEmpty(List<String> roles) {
            return roles == null ? List.of() : roles;
        }
    }
}
