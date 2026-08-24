package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.adapter.in.dto.PaymentRequest;
import github.lms.lemuel.payment.adapter.in.dto.PaymentResponse;
import github.lms.lemuel.payment.adapter.in.dto.TossCartConfirmRequest;
import github.lms.lemuel.payment.adapter.in.dto.TossPaymentConfirmRequest;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import github.lms.lemuel.web.security.ResourceOwnership;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST Controller for Payment API - Maps HTTP requests to use case ports
 */
@Tag(name = "Payment", description = "결제 생성/인증/캡처/환불 및 Toss 연동 API")
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final CreatePaymentPort createPaymentPort;
    private final AuthorizePaymentPort authorizePaymentPort;
    private final CapturePaymentPort capturePaymentPort;
    private final RefundPaymentPort refundPaymentPort;
    private final GetPaymentPort getPaymentPort;
    private final TossPaymentService tossPaymentService;

    public PaymentController(CreatePaymentPort createPaymentPort,
                             AuthorizePaymentPort authorizePaymentPort,
                             CapturePaymentPort capturePaymentPort,
                             RefundPaymentPort refundPaymentPort,
                             GetPaymentPort getPaymentPort,
                             TossPaymentService tossPaymentService) {
        this.createPaymentPort = createPaymentPort;
        this.authorizePaymentPort = authorizePaymentPort;
        this.capturePaymentPort = capturePaymentPort;
        this.refundPaymentPort = refundPaymentPort;
        this.getPaymentPort = getPaymentPort;
        this.tossPaymentService = tossPaymentService;
    }

    @Operation(summary = "결제 생성", description = "주문 ID와 결제 수단을 기반으로 결제를 생성한다. 상태: READY")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        CreatePaymentCommand command = new CreatePaymentCommand(
            request.getOrderId(),
            request.getPaymentMethod()
        );

        PaymentDomain paymentDomain = createPaymentPort.createPayment(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 인증", description = "결제 상태를 READY -> AUTHORIZED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인증 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @PatchMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorizePayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = authorizePaymentPort.authorizePayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 캡처", description = "결제 상태를 AUTHORIZED -> CAPTURED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캡처 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @PatchMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = capturePaymentPort.capturePayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 환불", description = "전액 또는 부분 환불. amount 미지정 시 전액 환불. 부분 환불은 Idempotency-Key 헤더 필수.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "환불 성공"),
            @ApiResponse(responseCode = "400", description = "환불 금액/멱등 키 오류"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "환불 금액이 잔여 환불 가능 금액을 초과")
    })
    @PatchMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id,
            @Parameter(description = "부분 환불 금액 (생략 시 전액)", required = false)
                @RequestParam(value = "amount", required = false) java.math.BigDecimal amount,
            @Parameter(description = "부분 환불 멱등 키", required = false)
                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(id, amount, idempotencyKey);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 단건 조회", description = "결제 ID로 결제 정보를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = getPaymentPort.getPayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 토스페이먼츠 결제 확인
     * POST /payments/toss/confirm
     */
    @Operation(summary = "Toss 결제 확인",
            description = "토스페이먼츠 단건 결제 승인 요청을 처리한다. 승인 전에 주문 소유권과 금액을 서버에서 대조한다. "
                    + "Idempotency-Key 헤더를 주면 동일 키의 재요청은 최초 승인 결과를 그대로 반환한다"
                    + "(미지정 시 paymentKey 가 멱등 키가 된다).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공(또는 동일 키 replay)"),
            @ApiResponse(responseCode = "400", description = "Toss 승인 실패"),
            @ApiResponse(responseCode = "403", description = "본인 소유가 아닌 주문"),
            @ApiResponse(responseCode = "404", description = "주문/결제를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "승인 금액이 주문 금액과 불일치")
    })
    @PostMapping("/toss/confirm")
    public ResponseEntity<PaymentResponse> confirmTossPayment(
            @Valid @RequestBody TossPaymentConfirmRequest request,
            @Parameter(description = "결제 승인 멱등 키(미지정 시 paymentKey 사용)", required = false)
                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDomain paymentDomain = tossPaymentService.confirmTossPayment(
                request.getDbOrderId(),
                request.getPaymentKey(),
                request.getTossOrderId(),
                request.getAmount(),
                callerUserIdOrAdminBypass(),
                idempotencyKey
        );
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 토스페이먼츠 장바구니 일괄 결제 확인
     * POST /payments/toss/cart/confirm
     */
    @Operation(summary = "Toss 장바구니 일괄 결제 확인", description = "여러 주문에 대한 토스페이먼츠 일괄 결제 승인 요청을 처리한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 승인 실패")
    })
    @PostMapping("/toss/cart/confirm")
    public ResponseEntity<List<PaymentResponse>> confirmTossCartPayment(
            @Valid @RequestBody TossCartConfirmRequest request,
            @Parameter(description = "결제 승인 멱등 키(미지정 시 paymentKey 사용)", required = false)
                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        List<PaymentDomain> payments = tossPaymentService.confirmTossCartPayment(
                request.getOrderIds(),
                request.getPaymentKey(),
                request.getTossOrderId(),
                request.getTotalAmount(),
                callerUserIdOrAdminBypass(),
                idempotencyKey
        );
        List<PaymentResponse> responses = payments.stream()
                .map(PaymentResponse::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 승인 요청자의 사용자 ID — 소유권 대조 기준값.
     *
     * <p>요청 본문의 {@code dbOrderId}/{@code orderIds} 는 신뢰하지 않는다(IDOR). 대조 상대는
     * <b>JWT 주체</b>에서만 파생하며, 판정 자체는 주문 소유자를 읽을 수 있는 서비스 계층이 한다.
     *
     * <p>ADMIN/MANAGER 는 {@code null} 을 돌려 소유권 대조를 건너뛴다 — 세무·문서함 컨트롤러와
     * 같은 정책이다(운영 지원 시 타인 주문 결제를 대행할 수 있어야 한다). 금액 대조는 운영자
     * 경로에서도 그대로 적용된다.
     */
    private Long callerUserIdOrAdminBypass() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (ResourceOwnership.isAdminOrManager(authentication)) {
            return null;
        }
        return ResourceOwnership.callerUserId(authentication);
    }
}
