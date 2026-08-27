package github.lms.lemuel.inquiry.adapter.in.web.dto;

import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 답변 등록 요청.
 *
 * <p>답변자를 받지 않는다 — 토큰이 정한다. 본문에서 받으면 "누가 답했는가"를 요청자가 적게 된다.
 */
public record AnswerInquiryRequest(
        @NotBlank(message = "답변 내용을 입력해 주세요.")
        @Size(max = InquiryAnswer.CONTENT_MAX,
                message = "답변은 " + InquiryAnswer.CONTENT_MAX + "자까지 쓸 수 있습니다.")
        String content) {
}
