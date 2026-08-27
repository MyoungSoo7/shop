package github.lms.lemuel.inquiry.adapter.in.web;

import github.lms.lemuel.inquiry.adapter.in.web.dto.AnswerInquiryRequest;
import github.lms.lemuel.inquiry.adapter.in.web.dto.InquiryResponse;
import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 문의 응대 콘솔 — {@code /admin/inquiries}.
 *
 * <p>인가는 <b>SecurityConfig 의 매처</b>가 한다({@code /admin/inquiries/** → ADMIN·MANAGER}).
 * 이 저장소에는 {@code @EnableMethodSecurity} 가 없어 {@code @PreAuthorize} 가 조용히 무효라,
 * 여기에 어노테이션을 붙여 두면 "막혀 있다"는 잘못된 인상만 남는다. 리뷰 콘솔과 같은 판단으로
 * MANAGER 까지 연다 — 문의 응대는 CS 업무 그 자체다.
 */
@Tag(name = "Admin Inquiry", description = "문의 응대 콘솔 (ADMIN·MANAGER)")
@RestController
@RequestMapping("/admin/inquiries")
public class AdminInquiryController {

    private final InquiryUseCase inquiryUseCase;

    public AdminInquiryController(InquiryUseCase inquiryUseCase) {
        this.inquiryUseCase = inquiryUseCase;
    }

    @Operation(summary = "답변 대기 목록",
            description = "오래된 순 — 먼저 물어본 사람이 먼저다. 판정은 저장된 상태 칼럼이 아니라 답변 유무다.")
    @GetMapping("/waiting")
    public ResponseEntity<List<InquiryResponse>> listWaiting() {
        return ResponseEntity.ok(inquiryUseCase.listWaiting().stream()
                .map(inquiry -> InquiryResponse.from(inquiry, true))
                .toList());
    }

    @Operation(summary = "문의 상세", description = "비밀글도 그대로 본다 — 답하려면 읽어야 한다.")
    @GetMapping("/{inquiryId}")
    public ResponseEntity<InquiryResponse> get(@PathVariable Long inquiryId) {
        Long viewerId = ResourceOwnership.callerUserId();
        return ResponseEntity.ok(InquiryResponse.from(inquiryUseCase.get(inquiryId, viewerId, true), true));
    }

    @Operation(summary = "답변 등록", description = "다는 순간 상태가 '답변 완료'가 된다. 그 뒤로 질문자는 수정할 수 없다.")
    @PostMapping("/{inquiryId}/answers")
    public ResponseEntity<InquiryResponse> answer(@PathVariable Long inquiryId,
                                                  @Valid @RequestBody AnswerInquiryRequest request) {
        Long answererId = ResourceOwnership.callerUserId();
        return ResponseEntity.ok(InquiryResponse.from(
                inquiryUseCase.answer(inquiryId, answererId, request.content()), true));
    }

    @Operation(summary = "답변 삭제",
            description = "지우는 순간 상태가 다시 '답변 대기'다. 어느 문의의 답변인지까지 대조하므로 "
                    + "다른 문의의 답변 번호를 넣으면 404 다.")
    @DeleteMapping("/{inquiryId}/answers/{answerId}")
    public ResponseEntity<InquiryResponse> deleteAnswer(@PathVariable Long inquiryId,
                                                        @PathVariable Long answerId) {
        return ResponseEntity.ok(InquiryResponse.from(
                inquiryUseCase.deleteAnswer(inquiryId, answerId), true));
    }
}
