package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.adapter.in.web.request.RefundAccountRequest;
import github.lms.lemuel.order.adapter.in.web.request.ReturnWaybillRequest;
import github.lms.lemuel.order.adapter.in.web.response.ReturnRequestResponse;
import github.lms.lemuel.order.application.port.in.ProcessOrderReturnUseCase;
import github.lms.lemuel.order.application.port.in.RequestOrderReturnUseCase;
import github.lms.lemuel.order.domain.ReturnRequestStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 반품·교환 처리 콘솔.
 *
 * <pre>
 *   GET  /admin/return-requests                       → 대기열 (기본: 열려 있는 신청 전부)
 *   GET  /admin/return-requests/{id}                  → 단건
 *   POST /admin/return-requests/{id}/approve          → 승인
 *   POST /admin/return-requests/{id}/reject           → 거절 (주문 상태 복귀)
 *   PUT  /admin/return-requests/{id}/return-waybill   → 회수 송장 대리 등록
 *   POST /admin/return-requests/{id}/collect          → 회수 확인 (재고 원복)
 *   POST /admin/return-requests/{id}/exchange-shipment→ 교환품 재배송 (배송 흐름 복귀)
 *   POST /admin/return-requests/{id}/refund           → 환불 실행 + 신청 종료
 *   PUT  /admin/return-requests/{id}/refund-account   → 환불 계좌 정정
 * </pre>
 *
 * <p><b>승인과 환불을 나눈 이유</b>: 반품은 물건이 돌아온 뒤에야 돈이 움직인다. 승인 버튼 하나가
 * 환불까지 하면 운영자는 물건을 받기 전에 승인할 수 없게 되고, 그러면 고객은 승인 없이 반송해야
 * 한다. 출고 전 취소({@code CANCEL})만 회수할 물건이 없어 승인이 곧 환불이다.
 *
 * <p><b>권한</b>: {@code SecurityConfig} 의 {@code /admin/return-requests/**} 매처가 전부다. 이
 * 설정에는 포괄 {@code /admin/**} 매처가 없어 매처를 빠뜨린 경로는 에러가 아니라 조용히
 * {@code anyRequest().authenticated()} 로 떨어진다. 환불 콘솔과 같은 CS 업무라 MANAGER 에게도 연다.
 * 게이트웨이 {@code Path} 술어에도 같은 접두사를 넣어야 한다 — 빠지면 여기까지 오지 못하고 404 다.
 */
@Tag(name = "Admin Return Request", description = "반품·교환 처리 콘솔")
@Validated
@RestController
@RequestMapping("/admin/return-requests")
@RequiredArgsConstructor
public class AdminReturnRequestController {

    private final ProcessOrderReturnUseCase processOrderReturnUseCase;
    private final RequestOrderReturnUseCase requestOrderReturnUseCase;

    @Operation(summary = "처리 대기열",
            description = "status 를 주지 않으면 열려 있는 신청(REQUESTED·APPROVED·COLLECTED) 전부. 오래된 순.")
    @GetMapping
    public ResponseEntity<List<ReturnRequestResponse>> queue(
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "100") int limit) {
        List<ReturnRequestStatus> statuses = status == null ? List.of()
                : status.stream().map(ReturnRequestStatus::fromString).toList();
        return ResponseEntity.ok(processOrderReturnUseCase.queue(statuses, limit).stream()
                .map(ReturnRequestResponse::from)
                .toList());
    }

    @Operation(summary = "신청 단건")
    @GetMapping("/{requestId}")
    public ResponseEntity<ReturnRequestResponse> get(@PathVariable Long requestId) {
        return ResponseEntity.ok(ReturnRequestResponse.from(requestOrderReturnUseCase.getById(requestId)));
    }

    @Operation(summary = "승인",
            description = "반품·교환은 회수를 기다린다. 출고 전 취소는 이 자리에서 환불까지 끝난다.")
    @PostMapping("/{requestId}/approve")
    public ResponseEntity<ReturnRequestResponse> approve(@PathVariable Long requestId, Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.approve(requestId, actor(principal))));
    }

    @Operation(summary = "거절", description = "주문 상태는 신청 직전으로 되돌아간다")
    @PostMapping("/{requestId}/reject")
    public ResponseEntity<ReturnRequestResponse> reject(@PathVariable Long requestId,
                                                        @RequestBody(required = false) ReasonRequest body,
                                                        Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.reject(requestId, body == null ? null : body.reason(), actor(principal))));
    }

    @Operation(summary = "회수 송장 대리 등록",
            description = "고객이 전화로 알려 온 송장을 운영자가 대신 적는다")
    @PutMapping("/{requestId}/return-waybill")
    public ResponseEntity<ReturnRequestResponse> registerReturnWaybill(
            @PathVariable Long requestId,
            @Valid @RequestBody ReturnWaybillRequest request,
            Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                requestOrderReturnUseCase.registerReturnWaybill(
                        requestId, request.carrier(), request.trackingNumber(), actor(principal))));
    }

    @Operation(summary = "회수 확인",
            description = "물건이 실제로 돌아왔다 — 이 시점에 재고가 판매 가능으로 복귀한다")
    @PostMapping("/{requestId}/collect")
    public ResponseEntity<ReturnRequestResponse> collect(@PathVariable Long requestId, Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.markCollected(requestId, actor(principal))));
    }

    @Operation(summary = "교환품 재배송", description = "주문이 SHIPPING_PENDING 으로 돌아가고 신청이 끝난다")
    @PostMapping("/{requestId}/exchange-shipment")
    public ResponseEntity<ReturnRequestResponse> shipExchange(@PathVariable Long requestId,
                                                              @Valid @RequestBody ReturnWaybillRequest request,
                                                              Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.shipExchange(
                        requestId, request.carrier(), request.trackingNumber(), actor(principal))));
    }

    @Operation(summary = "환불 실행", description = "계좌 환불 대상인데 계좌가 비어 있으면 막힌다")
    @PostMapping("/{requestId}/refund")
    public ResponseEntity<ReturnRequestResponse> refund(@PathVariable Long requestId, Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.completeRefund(requestId, actor(principal))));
    }

    @Operation(summary = "환불 계좌 정정", description = "오타로 반송되는 일이 잦다")
    @PutMapping("/{requestId}/refund-account")
    public ResponseEntity<ReturnRequestResponse> changeRefundAccount(@PathVariable Long requestId,
                                                                     @Valid @RequestBody RefundAccountRequest request,
                                                                     Principal principal) {
        return ResponseEntity.ok(ReturnRequestResponse.from(
                processOrderReturnUseCase.changeRefundAccount(
                        requestId, request.bankCode(), request.accountNumber(), request.holderName(),
                        actor(principal))));
    }

    public record ReasonRequest(String reason) {}

    private static String actor(Principal principal) {
        return principal == null ? "system" : principal.getName();
    }
}
