package github.lms.lemuel.inquiry.adapter.in.web.dto;

import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문의 응답.
 *
 * <p>상태({@code status})는 <b>저장된 값이 아니라</b> 답변 유무에서 계산된 값이다. 그래서 답변을
 * 지우면 같은 순간 이 값도 "답변 대기"로 돌아온다 — 레거시에서 목록과 상세가 어긋났던 지점이다.
 *
 * @param readable 본문을 볼 수 있는가. false 면 제목·본문이 가려진 채 온다. 화면은 이 값으로
 *                 자물쇠 뱃지를 그리고, 본문 자리에 "비밀글입니다"를 놓는다
 */
public record InquiryResponse(Long id,
                              Long userId,
                              String type,
                              String typeLabel,
                              Long productId,
                              Long orderId,
                              String subject,
                              String content,
                              boolean secret,
                              boolean readable,
                              String status,
                              String statusLabel,
                              LocalDateTime askedAt,
                              List<AnswerResponse> answers) {

    public static InquiryResponse from(Inquiry inquiry, boolean readable) {
        return new InquiryResponse(
                inquiry.id(),
                inquiry.userId(),
                inquiry.type().name(),
                inquiry.type().label(),
                inquiry.productId(),
                inquiry.orderId(),
                inquiry.subject(),
                inquiry.content(),
                inquiry.secret(),
                readable,
                inquiry.status().name(),
                inquiry.status().label(),
                inquiry.askedAt(),
                inquiry.answers().stream().map(AnswerResponse::from).toList());
    }

    public record AnswerResponse(Long id, Long answeredBy, String content, LocalDateTime answeredAt) {
        static AnswerResponse from(InquiryAnswer answer) {
            return new AnswerResponse(answer.id(), answer.answeredBy(), answer.content(), answer.answeredAt());
        }
    }
}
