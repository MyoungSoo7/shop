package github.lms.lemuel.inquiry.adapter.in.web.dto;

import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 문의 등록 요청.
 *
 * <p><b>작성자를 받지 않는다.</b> 레거시는 {@code USERID} 를 폼에서 받아 그대로 저장했다 —
 * 남의 아이디를 적으면 남의 이름으로 문의가 등록됐다. 여기서는 토큰이 정하므로 본문에 자리가 없다.
 */
public record AskInquiryRequest(
        @NotNull(message = "문의 종류를 골라 주세요.") InquiryType type,
        Long productId,
        Long orderId,
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = Inquiry.SUBJECT_MAX, message = "제목은 " + Inquiry.SUBJECT_MAX + "자까지 쓸 수 있습니다.")
        String subject,
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = Inquiry.CONTENT_MAX, message = "내용은 " + Inquiry.CONTENT_MAX + "자까지 쓸 수 있습니다.")
        String content,
        boolean secret) {

    public InquiryUseCase.AskCommand toCommand(Long userId) {
        return new InquiryUseCase.AskCommand(userId, type, productId, orderId, subject, content, secret);
    }
}
