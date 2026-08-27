package github.lms.lemuel.inquiry.adapter.in.web;

import github.lms.lemuel.inquiry.adapter.in.web.dto.AskInquiryRequest;
import github.lms.lemuel.inquiry.adapter.in.web.dto.EditInquiryRequest;
import github.lms.lemuel.inquiry.adapter.in.web.dto.InquiryResponse;
import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 문의 — {@code /inquiries}.
 *
 * <p><b>작성자는 요청 본문이 아니라 토큰이 정한다.</b> 레거시는 {@code USERID} 를 폼 파라미터로
 * 받아 그대로 넣었다 — 남의 아이디를 적어 보내면 남의 이름으로 문의가 등록됐고, 그 뒤로는
 * 그 사람만 볼 수 있었다. 여기서는 {@link ResourceOwnership#callerUserId()} 가 JWT 주체에서 꺼낸다 —
 * {@code Authentication} 을 <b>파라미터로 받지 않고</b> 홀더에서 읽는 것은 이 저장소의 관례이며,
 * 파라미터 주입은 보안 필터가 채우는 값이라 필터를 끈 슬라이스 테스트에서 말없이 null 이 된다.
 *
 * <p>SecurityConfig 에 이 경로의 별도 규칙이 없는 것은 의도된 것이다 — {@code anyRequest()
 * .authenticated()} 로 떨어지고, <b>누구의 것인지</b>는 서비스가 소유권 대조로 정한다.
 * 관리자 표면은 {@code /admin/inquiries} 로 따로 있고 그쪽은 매처가 있다.
 */
@Tag(name = "Inquiry", description = "상품 문의 · 주문 문의 · 1:1 문의")
@RestController
@RequestMapping("/inquiries")
public class InquiryController {

    private final InquiryUseCase inquiryUseCase;

    public InquiryController(InquiryUseCase inquiryUseCase) {
        this.inquiryUseCase = inquiryUseCase;
    }

    @Operation(summary = "문의 등록",
            description = "종류(PRODUCT·ORDER·GENERAL)에 따라 대상 상품·주문이 필요하다. "
                    + "알림 발송이 실패해도 등록은 성공이다 — 두 성패를 섞지 않는다.")
    @PostMapping
    public ResponseEntity<InquiryResponse> ask(@Valid @RequestBody AskInquiryRequest request) {
        Long userId = ResourceOwnership.callerUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InquiryResponse.from(inquiryUseCase.ask(request.toCommand(userId)), true));
    }

    @Operation(summary = "내 문의 목록", description = "최신순. type 을 주면 그 종류만 본다.")
    @GetMapping
    public ResponseEntity<List<InquiryResponse>> listMine(@RequestParam(required = false) InquiryType type) {
        Long userId = ResourceOwnership.callerUserId();
        return ResponseEntity.ok(inquiryUseCase.listMine(userId, type).stream()
                .map(inquiry -> InquiryResponse.from(inquiry, true))
                .toList());
    }

    @Operation(summary = "상품에 달린 문의 목록",
            description = "공개 상품 문의는 본문까지, 비밀글은 제목이 가려진 채 줄만 남는다. "
                    + "작성자 본인과 관리자에게는 원문 그대로 온다.")
    @GetMapping("/products/{productId}")
    public ResponseEntity<List<InquiryResponse>> listForProduct(@PathVariable Long productId) {
        Long viewerId = ResourceOwnership.callerUserId();
        boolean admin = ResourceOwnership.isAdminOrManager();
        return ResponseEntity.ok(inquiryUseCase.listForProduct(productId, viewerId, admin).stream()
                .map(inquiry -> InquiryResponse.from(inquiry, inquiry.isReadableBy(viewerId, admin)))
                .toList());
    }

    @Operation(summary = "문의 상세", description = "본인·관리자, 또는 공개된 상품 문의만 볼 수 있다.")
    @GetMapping("/{inquiryId}")
    public ResponseEntity<InquiryResponse> get(@PathVariable Long inquiryId) {
        Long viewerId = ResourceOwnership.callerUserId();
        boolean admin = ResourceOwnership.isAdminOrManager();
        return ResponseEntity.ok(InquiryResponse.from(inquiryUseCase.get(inquiryId, viewerId, admin), true));
    }

    @Operation(summary = "문의 수정",
            description = "답변이 달린 뒤에는 409 다. 답을 받은 뒤 질문을 바꾸면 서로 맞지 않는 한 쌍이 남는다.")
    @PutMapping("/{inquiryId}")
    public ResponseEntity<InquiryResponse> edit(@PathVariable Long inquiryId,
                                                @Valid @RequestBody EditInquiryRequest request) {
        Long userId = ResourceOwnership.callerUserId();
        return ResponseEntity.ok(InquiryResponse.from(
                inquiryUseCase.edit(inquiryId, userId, request.subject(), request.content(), request.secret()),
                true));
    }

    @Operation(summary = "문의 철회", description = "답변이 달린 뒤에는 409 다.")
    @DeleteMapping("/{inquiryId}")
    public ResponseEntity<Void> withdraw(@PathVariable Long inquiryId) {
        inquiryUseCase.withdraw(inquiryId, ResourceOwnership.callerUserId());
        return ResponseEntity.noContent().build();
    }
}
