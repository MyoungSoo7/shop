package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.adapter.in.web.request.CreateOrderRequest;
import github.lms.lemuel.order.adapter.in.web.response.OrderResponse;
import github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Order API Controller
 */
@Tag(name = "Order", description = "주문 생성/조회/상태 변경 API")
@Validated
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final IdempotentMultiItemOrderUseCase createMultiItemOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;
    private final CancelOrderItemsUseCase cancelOrderItemsUseCase;
    private final github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;

    @Operation(summary = "주문 생성 (단건)", description = "단일 상품 주문 — 레거시 호환 경로.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Order order = createOrderUseCase.createOrder(
                new CreateOrderUseCase.CreateOrderCommand(request.getUserId(), request.getProductId(), request.getAmount())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @Operation(summary = "주문 생성 (다건/SKU)",
            description = "장바구니 다건 주문. SKU(variantId) 지정 시 자동 재고 차감. "
                    + "Idempotency-Key 헤더를 주면 동일 키의 중복 제출(더블클릭·재시도)을 분산 락 + DB UNIQUE 로 차단해 "
                    + "1건만 생성하고, 재요청 시 기존 주문을 그대로 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "다건 주문 생성 성공"),
            @ApiResponse(responseCode = "409", description = "재고 부족·동시성 충돌·중복 제출 충돌")
    })
    @PostMapping("/multi")
    public ResponseEntity<OrderResponse> createMultiItemOrder(
            @Valid @RequestBody MultiItemOrderRequest request,
            @Parameter(description = "중복 주문 방지용 멱등 키(선택). 같은 키 재요청은 동일 주문을 반환.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        List<CreateMultiItemOrderUseCase.Line> lines = request.lines().stream()
                .map(l -> new CreateMultiItemOrderUseCase.Line(l.productId(), l.variantId(), l.quantity()))
                .toList();
        Order order = createMultiItemOrderUseCase.create(request.userId(), lines, request.couponCode(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    public record MultiItemOrderRequest(
            @jakarta.validation.constraints.NotNull Long userId,
            @jakarta.validation.constraints.NotEmpty List<LineRequest> lines,
            String couponCode) {}

    public record LineRequest(
            @jakarta.validation.constraints.NotNull Long productId,
            Long variantId,
            @jakarta.validation.constraints.Min(1) int quantity) {}

    @Operation(summary = "주문 단건 조회", description = "주문 ID로 주문을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "주문 ID", required = true) @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long id) {
        Order order = getOrderUseCase.getOrderById(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "사용자별 주문 목록 조회", description = "지정한 사용자의 모든 주문을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getUserOrders(
            @Parameter(description = "사용자 ID", required = true) @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        List<OrderResponse> orders = getOrderUseCase.getOrdersByUserId(userId, status, from, to)
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @Operation(summary = "전체 주문 조회 (관리자)", description = "시스템의 모든 주문을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/admin/all")
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<OrderResponse> orders = getOrderUseCase.getAllOrders()
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @Operation(summary = "주문 취소", description = "주문 상태를 CANCELED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "취소할 수 없는 상태")
    })
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @Parameter(description = "주문 ID", required = true) @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long id) {
        Order order = changeOrderStatusUseCase.cancelOrder(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "주문 취소 신청", description = "사용자가 주문 취소를 신청한다.")
    @PostMapping("/{id}/cancellation-request")
    public ResponseEntity<OrderResponse> requestCancellation(
            @PathVariable Long id,
            @RequestBody StatusReasonRequest request,
            Principal principal) {
        Order order = changeOrderStatusUseCase.requestCancellation(id, request.reason(), actor(principal));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "환불 신청", description = "사용자가 결제 완료 주문의 환불을 신청한다.")
    @PostMapping("/{id}/refund-request")
    public ResponseEntity<OrderResponse> requestRefund(
            @PathVariable Long id,
            @RequestBody StatusReasonRequest request,
            Principal principal) {
        Order order = changeOrderStatusUseCase.requestRefund(id, request.reason(), actor(principal));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "취소 승인 (관리자)", description = "관리자가 취소 신청을 승인한다.")
    @PostMapping("/admin/{id}/cancellation-approve")
    public ResponseEntity<OrderResponse> approveCancellation(
            @PathVariable Long id,
            @RequestBody StatusReasonRequest request,
            Principal principal) {
        Order order = changeOrderStatusUseCase.approveCancellation(id, request.reason(), actor(principal));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "환불 승인 (관리자)", description = "관리자가 환불 신청을 승인한다.")
    @PostMapping("/admin/{id}/refund-approve")
    public ResponseEntity<OrderResponse> approveRefund(
            @PathVariable Long id,
            @RequestBody StatusReasonRequest request,
            Principal principal) {
        Order order = changeOrderStatusUseCase.approveRefund(id, request.reason(), actor(principal));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "배송 상태 변경 (관리자)", description = "SHIPPING_PENDING/IN_TRANSIT/DELIVERED 로 주문 상태를 변경한다.")
    @PatchMapping("/admin/{id}/shipping-status")
    public ResponseEntity<OrderResponse> changeShippingStatus(
            @PathVariable Long id,
            @RequestBody AdminStatusRequest request,
            Principal principal) {
        Order order = changeOrderStatusUseCase.changeShippingStatus(
                id, request.status(), request.reason(), actor(principal));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "취소·환불 신청 철회",
            description = "취소/환불 신청을 철회해 신청 직전 상태로 되돌린다. 복귀 상태는 상태 이력이 근거이며, "
                    + "그 신청을 낼 수 없었던 상태로는 되돌리지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "철회 성공"),
            @ApiResponse(responseCode = "403", description = "타인 주문"),
            @ApiResponse(responseCode = "409", description = "철회할 신청이 없음")
    })
    @PostMapping("/{id}/request-withdraw")
    public ResponseEntity<OrderResponse> withdrawRequest(
            @PathVariable @Positive Long id,
            @RequestBody(required = false) StatusReasonRequest request,
            Principal principal) {
        Order order = getOrderUseCase.getOrderById(id);
        ResourceOwnership.requireSelfOrAdmin(order.getUserId());
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(OrderResponse.from(
                withdrawOrderRequestUseCase.withdraw(id, reason, actor(principal))));
    }

    @Operation(summary = "주문 라인 부분 취소",
            description = "지정한 주문 라인만 취소한다. 재고 복원 · 배송비 재산정 · 부분 환불이 한 트랜잭션에서 처리되며, "
                    + "무료배송 조건이 깨지면 면제됐던 배송비가 되살아나 환불액에서 차감된다. 출고 전까지만 가능.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "403", description = "타인 주문"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "출고 후 등 취소 불가 상태")
    })
    @PostMapping("/{id}/items/cancel")
    public ResponseEntity<CancelOrderItemsUseCase.Result> cancelItems(
            @PathVariable @Positive Long id,
            @Valid @RequestBody CancelItemsRequest request,
            Principal principal) {
        // IDOR 방지 — 취소 대상 주문의 소유자를 JWT 주체와 대조한다(요청 파라미터의 userId 를 믿지 않는다).
        Order order = getOrderUseCase.getOrderById(id);
        ResourceOwnership.requireSelfOrAdmin(order.getUserId());
        return ResponseEntity.ok(cancelOrderItemsUseCase.cancelItems(
                id, request.itemIds(), request.reason(), actor(principal)));
    }

    private static String actor(Principal principal) {
        return principal == null ? "system" : principal.getName();
    }

    public record CancelItemsRequest(
            @jakarta.validation.constraints.NotEmpty(message = "취소할 주문 라인을 지정해야 합니다")
            List<Long> itemIds,
            String reason) {}

    public record StatusReasonRequest(String reason) {}
    public record AdminStatusRequest(String status, String reason) {}
}
