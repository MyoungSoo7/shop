package github.lms.lemuel.inquiry.adapter.in.web.dto;

import github.lms.lemuel.inquiry.domain.Inquiry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문의 수정 요청.
 *
 * <p>종류와 대상(상품·주문)은 없다. 한 번 정해지면 바뀌지 않는다 — 상품 문의를 1:1 문의로
 * 바꿀 수 있게 두면, 상품 페이지에 걸려 있던 질문이 아무 흔적 없이 사라진다.
 */
public record EditInquiryRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = Inquiry.SUBJECT_MAX, message = "제목은 " + Inquiry.SUBJECT_MAX + "자까지 쓸 수 있습니다.")
        String subject,
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = Inquiry.CONTENT_MAX, message = "내용은 " + Inquiry.CONTENT_MAX + "자까지 쓸 수 있습니다.")
        String content,
        boolean secret) {
}
