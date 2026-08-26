package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.adapter.in.web.request.RefundAccountRequest;
import github.lms.lemuel.order.adapter.in.web.request.ReturnWaybillRequest;
import github.lms.lemuel.order.adapter.in.web.request.SubmitReturnRequest;
import github.lms.lemuel.order.adapter.in.web.response.ReturnRequestResponse;
import github.lms.lemuel.order.application.port.in.ProcessOrderReturnUseCase;
import github.lms.lemuel.order.application.port.in.RequestOrderReturnUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.ReturnRequestType;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 고객이 내는 반품·교환·취소 신청.
 *
 * <pre>
 *   POST /orders/{orderId}/return-requests                        → 신청
 *   GET  /orders/{orderId}/return-requests                        → 그 주문의 신청 이력
 *   PUT  /orders/{orderId}/return-requests/{requestId}/waybill    → 회수 송장 등록
 *   PUT  /orders/{orderId}/return-requests/{requestId}/refund-account → 환불 계좌 등록·정정
 *   POST /orders/{orderId}/return-requests/{requestId}/withdraw   → 신청 철회
 * </pre>
 *
 * <p><b>경로가 {@code /orders} 아래인 이유</b>는 두 가지다. 게이트웨이는 {@code /orders/**} 를 이미
 * 이 서비스로 보내고 있어 별도 술어가 필요 없고, 더 중요하게는 모든 경로가 {@code orderId} 를
 * 지나게 되어 주인 대조({@link #requireOwner})를 빠뜨릴 수 없다. {@code /return-requests/{id}} 로
 * 평평하게 뚫으면 신청 번호만으로 남의 신청에 송장을 붙일 수 있다.
 *
 * <p>그래서 {@code requestId} 도 항상 그 {@code orderId} 의 것인지 확인한다 — 내 주문 번호 + 남의
 * 신청 번호 조합을 막는다.
 */
@Tag(name = "Order Return Request", description = "반품·교환·취소 신청 (고객)")
@Validated
@RestController
@RequestMapping("/orders/{orderId}/return-requests")
@RequiredArgsConstructor
public class OrderReturnRequestController {

    private final RequestOrderReturnUseCase requestOrderReturnUseCase;
    private final ProcessOrderReturnUseCase processOrderReturnUseCase;
    private final GetOrderUseCase getOrderUseCase;

    @Operation(summary = "반품·교환·취소 신청",
            description = "type 은 RETURN(반품) · EXCHANGE(교환) · CANCEL(취소). 무통장·가상계좌처럼 "
                    + "PG 로 되돌릴 수 없는 결제는 환불 계좌 3 칸이 함께 필요하다.")
    @PostMapping
    public ResponseEntity<ReturnRequestResponse> submit(@PathVariable Long orderId,
                                                        @Valid @RequestBody SubmitReturnRequest request,
                                                        Principal principal) {
        Order order = getOrderUseCase.getOrderById(orderId);
        ResourceOwnership.requireSelfOrAdmin(order.getUserId());

        OrderReturnRequest created = requestOrderReturnUseCase.submit(
                new RequestOrderReturnUseCase.SubmitCommand(
                        orderId,
                        order.getUserId(),
                        ReturnRequestType.fromString(request.type()),
                        request.reasonCode(),
                        request.reasonDetail(),
                        request.refundBankCode(),
                        request.refundAccountNumber(),
                        request.refundAccountHolder(),
                        actor(principal)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReturnRequestResponse.from(created));
    }

    @Operation(summary = "신청 이력", description = "그 주문의 신청 전부 — 최근 것이 앞")
    @GetMapping
    public ResponseEntity<List<ReturnRequestResponse>> history(@PathVariable Long orderId) {
        requireOwner(orderId);
        return ResponseEntity.ok(requestOrderReturnUseCase.findByOrderId(orderId).stream()
                .map(ReturnRequestResponse::from)
                .toList());
    }

    @Operation(summary = "회수 송장 등록", description = "고객이 반송한 택배사·송장 번호")
    @PutMapping("/{requestId}/waybill")
    public ResponseEntity<ReturnRequestResponse> registerWaybill(@PathVariable Long orderId,
                                                                 @PathVariable Long requestId,
                                                                 @Valid @RequestBody ReturnWaybillRequest request,
                                                                 Principal principal) {
        requireOwnedRequest(orderId, requestId);
        return ResponseEntity.ok(ReturnRequestResponse.from(
                requestOrderReturnUseCase.registerReturnWaybill(
                        requestId, request.carrier(), request.trackingNumber(), actor(principal))));
    }

    @Operation(summary = "환불 계좌 등록·정정",
            description = "신청할 때 계좌를 못 냈거나 오타로 반송된 경우. 오타 정정이 잦아 별도 경로로 둔다.")
    @PutMapping("/{requestId}/refund-account")
    public ResponseEntity<ReturnRequestResponse> changeRefundAccount(@PathVariable Long orderId,
                                                                     @PathVariable Long requestId,
                                                                     @Valid @RequestBody RefundAccountRequest request,
                                                                     Principal principal) {
        requireOwnedRequest(orderId, requestId);
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.changeRefundAccount(
                        requestId, request.bankCode(), request.accountNumber(), request.holderName(),
                        actor(principal))));
    }

    @Operation(summary = "신청 철회", description = "주문 상태도 신청 직전으로 되돌아간다")
    @PostMapping("/{requestId}/withdraw")
    public ResponseEntity<ReturnRequestResponse> withdraw(@PathVariable Long orderId,
                                                          @PathVariable Long requestId,
                                                          @RequestBody(required = false) WithdrawRequest body,
                                                          Principal principal) {
        requireOwnedRequest(orderId, requestId);
        String reason = body == null ? null : body.reason();
        return ResponseEntity.ok(ReturnRequestResponse.from(
                requestOrderReturnUseCase.withdraw(requestId, reason, actor(principal))));
    }

    public record WithdrawRequest(String reason) {}

    private void requireOwner(Long orderId) {
        ResourceOwnership.requireSelfOrAdmin(getOrderUseCase.getOrderById(orderId).getUserId());
    }

    /** 주인 대조 + 그 신청이 정말 이 주문의 것인지. 둘 중 하나만 해서는 막히지 않는다. */
    private void requireOwnedRequest(Long orderId, Long requestId) {
        requireOwner(orderId);
        OrderReturnRequest request = requestOrderReturnUseCase.getById(requestId);
        if (!request.getOrderId().equals(orderId)) {
            throw new OrderInvariantViolationException("해당 주문의 신청이 아닙니다");
        }
    }

    private static String actor(Principal principal) {
        return principal == null ? "system" : principal.getName();
    }
}
