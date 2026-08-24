package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase;
import github.lms.lemuel.payment.application.service.RefundSplitPaymentService;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.web.security.ResourceOwnership;
import org.springframework.security.core.context.SecurityContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Split Payment", description = "분할결제 (포인트+카드+상품권)")
@RestController
@RequestMapping("/payments/split")
public class SplitPaymentController {

    private final CreateSplitPaymentUseCase createUseCase;
    private final RefundSplitPaymentService refundService;
    private final github.lms.lemuel.payment.application.port.in.ConfirmDepositUseCase confirmDepositUseCase;

    public SplitPaymentController(CreateSplitPaymentUseCase createUseCase,
                                   RefundSplitPaymentService refundService,
                                   github.lms.lemuel.payment.application.port.in.ConfirmDepositUseCase confirmDepositUseCase) {
        this.createUseCase = createUseCase;
        this.refundService = refundService;
        this.confirmDepositUseCase = confirmDepositUseCase;
    }

    @Operation(summary = "텐더 결제 생성 (분할 포함)",
            description = "지불수단(포인트/상품권/카드) 1 개 이상으로 결제한다. 포인트·상품권 전액 결제도 "
                    + "이 경로로 들어온다 — 일반 결제 경로는 지불수단을 모델링하지 않아 원장 차감·복원이 "
                    + "걸릴 자리가 없다. 합계가 주문 금액과 정확히 일치해야 한다.")
    @PostMapping
    public ResponseEntity<SplitPaymentResponse> create(@Valid @RequestBody SplitPaymentRequest request) {
        List<CreateSplitPaymentUseCase.TenderRequest> tenders = request.tenders().stream()
                .map(t -> new CreateSplitPaymentUseCase.TenderRequest(t.type(), t.amount()))
                .toList();
        // 결제 주체는 요청 본문이 아니라 JWT 에서 파생한다 — 본문의 userId 를 믿으면
        // 남의 포인트로 결제할 수 있다(IDOR).
        long actorUserId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        PaymentDomain p = createUseCase.createWithTenders(request.orderId(), tenders, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(SplitPaymentResponse.from(p));
    }

    @Operation(summary = "입금 확인 — 가상계좌·무통장 결제 확정",
            description = "돈이 실제로 들어왔을 때 부른다. 여기서 비로소 PG 매입·포인트 선점 확정·"
                    + "주문 PAID·정산 이벤트가 일어난다. 같은 통보가 여러 번 와도 안전하다(멱등). "
                    + "실 운영에서는 PG 입금 통보(웹훅)가 이 경로를 부른다.")
    @PostMapping("/{paymentId}/confirm-deposit")
    public ResponseEntity<SplitPaymentResponse> confirmDeposit(@PathVariable Long paymentId) {
        // 선점 확정의 감사 주체도 JWT 에서 파생한다 — 본문의 userId 를 믿으면 남의 잠금을 건드린다.
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        long actorUserId = ResourceOwnership.callerUserId(authentication);
        // 소유권 대조 기준. 운영자(ADMIN·MANAGER)는 대행 확정이 필요하므로 우회하고,
        // 일반 사용자는 <b>자기 주문의 결제만</b> 확정할 수 있다 — 이 구분이 없으면 paymentId 만
        // 알면 남의 결제를 확정해 그 사람의 포인트 선점을 소진시킬 수 있다(IDOR).
        Long ownerUserId = ResourceOwnership.isAdminOrManager(authentication) ? null : actorUserId;
        PaymentDomain p = confirmDepositUseCase.confirmDeposit(paymentId, actorUserId, ownerUserId);
        return ResponseEntity.ok(SplitPaymentResponse.from(p));
    }

    @Operation(summary = "분할결제 환불 — 역순 처리",
            description = "sequence 역순으로 외부 PG → 내부 잔액 순서로 환불. 부분환불 지원.")
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<SplitPaymentResponse> refund(@PathVariable Long paymentId,
                                                       @RequestBody RefundRequest request) {
        PaymentDomain p = refundService.refundSplit(paymentId, request.amount());
        return ResponseEntity.ok(SplitPaymentResponse.from(p));
    }

    public record SplitPaymentRequest(
            @NotNull Long orderId,
            @NotEmpty List<TenderRequestDto> tenders) {}

    public record TenderRequestDto(@NotNull TenderType type, @Positive BigDecimal amount) {}

    public record RefundRequest(@Positive BigDecimal amount) {}

    public record SplitPaymentResponse(Map<String, Object> payment, List<Map<String, Object>> tenders) {
        static SplitPaymentResponse from(PaymentDomain p) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", p.getId());
            body.put("orderId", p.getOrderId());
            body.put("amount", p.getAmount());
            body.put("refundedAmount", p.getRefundedAmount());
            body.put("status", p.getStatus().name());
            body.put("paymentMethod", p.getPaymentMethod());
            body.put("isSplit", p.isSplit());

            List<Map<String, Object>> ts = p.getTenders().stream().map(SplitPaymentResponse::toTenderMap).toList();
            return new SplitPaymentResponse(body, ts);
        }

        private static Map<String, Object> toTenderMap(PaymentTender t) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("type", t.getType().name());
            m.put("amount", t.getAmount());
            m.put("refundedAmount", t.getRefundedAmount());
            m.put("refundableAmount", t.getRefundableAmount());
            m.put("pgTransactionId", t.getPgTransactionId());
            m.put("status", t.getStatus().name());
            m.put("sequence", t.getSequence());
            return m;
        }
    }
}
