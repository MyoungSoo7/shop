package github.lms.lemuel.operation.board.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record BoardCommentRequest(
        @NotBlank(message = "댓글 내용은 필수입니다.") String content,
        /** 답글이면 부모 댓글 식별자. 답글의 답글은 도메인이 막는다(1단까지). */
        Long parentId) {
}
