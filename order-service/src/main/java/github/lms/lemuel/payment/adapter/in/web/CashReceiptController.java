package github.lms.lemuel.payment.adapter.in.web;

import github.lms.lemuel.payment.application.port.in.CashReceiptUseCase;
import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 현금영수증 — 발급 신청과 조회.
 *
 * <p>주체는 언제나 JWT 에서 파생한다. 요청 본문에 userId 를 두면 결제 id 만 아는 사람이 남의 결제로
 * 자기 소득공제를 받을 수 있다(IDOR). 소유권 대조는 서비스가 결제→주문→주문자로 확인한다.
 *
 * <p><b>응답에는 식별번호 원문을 싣지 않는다</b> — 뒤 4 자리만. 조회 한 번이 뚫리면 휴대폰번호·
 * 사업자번호가 통째로 새는 종류의 데이터다.
 */
@Tag(name = "Cash Receipt", description = "현금영수증(계좌이체·가상계좌 결제)")
@RestController
@RequestMapping("/api/payments")
public class CashReceiptController {

    private final CashReceiptUseCase cashReceiptUseCase;

    public CashReceiptController(CashReceiptUseCase cashReceiptUseCase) {
        this.cashReceiptUseCase = cashReceiptUseCase;
    }

    @Operation(summary = "현금영수증 발급 신청(주문 기준)",
            description = "계좌이체·가상계좌로 입금이 확인된 결제에만 발급한다. 카드 결제는 카드사 "
                    + "매출전표로 이미 신고되어 대상이 아니다(이중 공제 방지). "
                    + "고객이 손에 쥔 식별자는 주문번호이므로 주문 기준으로 받는다.")
    @PostMapping("/by-order/{orderId}/cash-receipt")
    public ResponseEntity<CashReceiptResponse> issue(@PathVariable Long orderId,
                                                    @Valid @RequestBody IssueRequest request) {
        long userId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        CashReceipt receipt = cashReceiptUseCase.issueForOrder(orderId, userId,
                request.purpose(), request.identifierType(), request.identifierValue());
        return ResponseEntity.ok(CashReceiptResponse.from(receipt));
    }

    @Operation(summary = "현금영수증 조회(주문 기준)",
            description = "발급 이력이 없으면 204. 식별번호는 뒤 4 자리만 노출한다.")
    @GetMapping("/by-order/{orderId}/cash-receipt")
    public ResponseEntity<CashReceiptResponse> get(@PathVariable Long orderId) {
        long userId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        return cashReceiptUseCase.findActiveByOrder(orderId, userId)
                .map(receipt -> ResponseEntity.ok(CashReceiptResponse.from(receipt)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record IssueRequest(
            @NotNull CashReceiptPurpose purpose,
            @NotNull CashReceiptIdentifier.Type identifierType,
            @NotBlank String identifierValue) {
    }

    public record CashReceiptResponse(
            Long id,
            Long paymentId,
            Long orderId,
            String purpose,
            String purposeLabel,
            String identifierType,
            String maskedIdentifier,
            BigDecimal totalAmount,
            BigDecimal supplyAmount,
            BigDecimal vatAmount,
            String status,
            String approvalNumber,
            String failureReason,
            LocalDateTime issuedAt) {

        static CashReceiptResponse from(CashReceipt r) {
            return new CashReceiptResponse(
                    r.getId(), r.getPaymentId(), r.getOrderId(),
                    r.getPurpose().name(), r.getPurpose().label(),
                    r.getIdentifier().getType().name(), r.getIdentifier().masked(),
                    r.getTotalAmount(), r.getSupplyAmount(), r.getVatAmount(),
                    r.getStatus().name(), r.getApprovalNumber(), r.getFailureReason(),
                    r.getIssuedAt());
        }
    }
}
