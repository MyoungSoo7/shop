package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.application.port.in.ManagePostUseCase.PostContentCommand;
import jakarta.validation.constraints.NotBlank;

/**
 * 게시글 작성·수정 요청.
 *
 * <p><b>작성자 필드가 없다.</b> 주체는 JWT 에서만 온다 — 요청에 담는 순간 남의 이름을 달 수 있다.
 * 길이·비밀글 허용 여부 같은 <b>의미</b> 검증은 도메인이 한다(여기 {@code @Size} 를 붙이면 규칙이 둘이 된다).
 */
public record BoardPostRequest(
        @NotBlank(message = "제목은 필수입니다.") String title,
        @NotBlank(message = "본문은 필수입니다.") String content,
        String categoryCode,
        boolean secret) {

    public PostContentCommand toCommand() {
        return new PostContentCommand(title, content, categoryCode, secret);
    }
}
