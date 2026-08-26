package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.application.port.in.SendGiftUseCase;
import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.GiftClaimStatus;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 선물 보내기 — <b>보내는 사람</b> 쪽 경로.
 *
 * <pre>
 *   POST /orders/gifts                  → 선물 주문 생성 + 링크 발송
 *   GET  /orders/{orderId}/gift         → 진행 상황
 *   POST /orders/{orderId}/gift/resend  → 링크 재발송(새 토큰)
 *   POST /orders/{orderId}/gift/cancel  → 링크 회수
 * </pre>
 *
 * <p>받는 사람 쪽은 로그인 없이 열려야 해서 {@link GiftClaimController} 로 완전히 분리돼 있다.
 * 한 컨트롤러에 섞으면 인증이 필요한 경로와 아닌 경로가 같은 접두사를 공유하게 되어,
 * {@code SecurityConfig} 의 {@code permitAll} 이 의도보다 넓게 열린다.
 *
 * <p><b>응답에 링크(평문 토큰)를 담지 않는다.</b> 보내는 사람이 그 링크를 열어도 인증번호는 받는
 * 사람 번호로 가므로 주소를 낼 수는 없지만, 굳이 화면·로그·클립보드에 열쇠를 한 벌 더 뿌릴 이유가
 * 없다. 발송이 실패했으면 {@code linkDelivered=false} 를 보고 재발송을 누르면 된다.
 */
@Tag(name = "Order Gift", description = "선물 주문 (보내는 사람)")
@Validated
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderGiftController {

    private final SendGiftUseCase sendGiftUseCase;
    private final GetOrderUseCase getOrderUseCase;

    @Operation(summary = "선물 주문 생성",
            description = "받는 사람의 이름과 휴대폰 번호만 받는다. 배송지는 받는 사람이 링크에서 직접 낸다. "
                    + "Idempotency-Key 헤더는 일반 다건 주문과 같은 규칙으로 동작한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "선물 주문 생성 (발송 실패해도 201 — linkDelivered 로 구분)"),
            @ApiResponse(responseCode = "409", description = "재고 부족·중복 제출·이미 링크가 있는 주문")
    })
    @PostMapping("/gifts")
    public ResponseEntity<SentGiftResponse> send(
            @Valid @RequestBody GiftOrderRequest request,
            @Parameter(description = "중복 주문 방지용 멱등 키(선택)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // 남의 userId 로 주문하면 그 사람의 쿠폰·포인트가 소진된다. 일반 주문과 같은 대조다.
        ResourceOwnership.requireSelfOrAdmin(request.userId());

        List<CreateMultiItemOrderUseCase.Line> lines = request.lines().stream()
                .map(l -> new CreateMultiItemOrderUseCase.Line(l.productId(), l.variantId(), l.quantity()))
                .toList();

        SendGiftUseCase.SentGift sent = sendGiftUseCase.send(
                new SendGiftUseCase.SendCommand(request.userId(), lines, request.couponCode(),
                        request.recipientName(), request.recipientPhone(), request.message()),
                idempotencyKey);

        return ResponseEntity.status(HttpStatus.CREATED).body(SentGiftResponse.from(sent));
    }

    @Operation(summary = "선물 진행 상황", description = "받는 사람이 어디까지 왔는지 — 번호는 가려서 나간다")
    @GetMapping("/{orderId}/gift")
    public ResponseEntity<GiftStatusResponse> status(@PathVariable Long orderId) {
        requireSender(orderId);
        return ResponseEntity.ok(GiftStatusResponse.from(sendGiftUseCase.getByOrderId(orderId)));
    }

    @Operation(summary = "링크 재발송",
            description = "새 토큰으로 다시 보낸다. 이전 링크는 즉시 무효가 된다 — 잘못된 번호로 나간 링크를 "
                    + "살려 둘 이유가 없다.")
    @PostMapping("/{orderId}/gift/resend")
    public ResponseEntity<ResendResponse> resend(@PathVariable Long orderId) {
        requireSender(orderId);
        return ResponseEntity.ok(new ResendResponse(sendGiftUseCase.resendLink(orderId)));
    }

    @Operation(summary = "링크 회수",
            description = "링크를 더 이상 쓸 수 없게 한다. 주문·결제는 취소되지 않는다 — 환불은 반품·취소 신청 경로다.")
    @PostMapping("/{orderId}/gift/cancel")
    public ResponseEntity<GiftStatusResponse> cancel(@PathVariable Long orderId) {
        requireSender(orderId);
        return ResponseEntity.ok(GiftStatusResponse.from(sendGiftUseCase.cancel(orderId)));
    }

    /** 주문의 주인만 — 선물은 주문자와 수령자가 다르지만, 이 경로는 주문자의 것이다. */
    private void requireSender(Long orderId) {
        ResourceOwnership.requireSelfOrAdmin(getOrderUseCase.getOrderById(orderId).getUserId());
    }

    // ───────── 요청·응답 ─────────

    /** 배송지 칸이 없다는 것이 일반 주문 요청과의 유일한 차이다. */
    public record GiftOrderRequest(
            @NotNull Long userId,
            @NotEmpty List<OrderController.LineRequest> lines,
            String couponCode,
            @NotBlank @Size(max = 60) String recipientName,
            @NotBlank @Size(max = 40) String recipientPhone,
            @Size(max = 200) String message) {}

    /**
     * @param linkDelivered 안내 발송 성공 여부. false 여도 주문은 성립했다 — 화면이 재발송을 권해야 한다
     */
    public record SentGiftResponse(Long orderId,
                                   Long giftClaimId,
                                   GiftClaimStatus status,
                                   String maskedRecipientPhone,
                                   LocalDateTime expiresAt,
                                   boolean linkDelivered) {

        static SentGiftResponse from(SendGiftUseCase.SentGift sent) {
            GiftClaim claim = sent.claim();
            return new SentGiftResponse(sent.order().getId(), claim.getId(), claim.getStatus(),
                    claim.maskedRecipientPhone(), claim.getExpiresAt(), sent.linkDelivered());
        }
    }

    public record GiftStatusResponse(Long orderId,
                                     Long giftClaimId,
                                     GiftClaimStatus status,
                                     String recipientName,
                                     String maskedRecipientPhone,
                                     LocalDateTime expiresAt,
                                     LocalDateTime verifiedAt,
                                     LocalDateTime claimedAt) {

        static GiftStatusResponse from(GiftClaim claim) {
            return new GiftStatusResponse(claim.getOrderId(), claim.getId(), claim.getStatus(),
                    claim.getRecipientName(), claim.maskedRecipientPhone(),
                    claim.getExpiresAt(), claim.getVerifiedAt(), claim.getClaimedAt());
        }
    }

    public record ResendResponse(boolean linkDelivered) {}
}
