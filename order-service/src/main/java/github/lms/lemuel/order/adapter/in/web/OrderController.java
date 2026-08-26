package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.adapter.in.web.request.CreateOrderRequest;
import github.lms.lemuel.order.adapter.in.web.response.MultiItemOrderResponse;
import github.lms.lemuel.order.adapter.in.web.response.OrderResponse;
import github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.common.audit.application.AuditContext;
import github.lms.lemuel.order.application.port.in.PreviewCouponUseCase;
import github.lms.lemuel.order.application.port.in.RecordOrderConsentUseCase;
import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase;
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
    private final PreviewCouponUseCase previewCouponUseCase;
    private final SearchOrdersUseCase searchOrdersUseCase;
    private final github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;

    @Operation(summary = "주문 생성 (단건)", description = "단일 상품 주문 — 레거시 호환 경로.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        // 주문의 주인은 요청 본문이 아니라 토큰이 정한다. 없으면 로그인한 아무나 남의 이름으로
        // 주문을 만들 수 있다.
        ResourceOwnership.requireSelfOrAdmin(request.getUserId());
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
    public ResponseEntity<MultiItemOrderResponse> createMultiItemOrder(
            @Valid @RequestBody MultiItemOrderRequest request,
            @Parameter(description = "중복 주문 방지용 멱등 키(선택). 같은 키 재요청은 동일 주문을 반환.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // 남의 userId 로 주문하면 그 사람의 1회용 쿠폰까지 소진된다(쿠폰 검증·사용이 userId 기준).
        ResourceOwnership.requireSelfOrAdmin(request.userId());
        List<CreateMultiItemOrderUseCase.Line> lines = request.lines().stream()
                .map(l -> new CreateMultiItemOrderUseCase.Line(l.productId(), l.variantId(), l.quantity()))
                .toList();
        // 배송지는 이 경로에서 필수다. 요청 레코드에서 @NotNull 로 막지 않는 이유는 같은 레코드를
        // /coupon-preview 가 쓰기 때문이다 — 할인액만 계산하는 조회에 배송지를 요구할 이유가 없다.
        if (request.shippingAddress() == null) {
            throw new IllegalArgumentException("배송지(shippingAddress)는 필수입니다");
        }
        Order order = createMultiItemOrderUseCase.create(request.userId(), lines, request.couponCode(),
                request.shippingAddress().toSnapshot(), toConsentSubmission(request.consents()),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(MultiItemOrderResponse.from(order));
    }

    @Operation(summary = "쿠폰 미리보기 (장바구니 기준)",
            description = "주문을 만들지 않고 할인액만 계산한다. 주문 생성과 같은 라인 입력을 받아 같은 상품 마스터에서 "
                    + "단가·카테고리를 해석하므로, 여기서 보이는 할인액이 결제에 그대로 적용된다. "
                    + "상품·카테고리 전용 쿠폰은 이 경로로만 정확히 계산된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계산 결과(쿠폰 사용 불가도 200 + valid=false)"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PostMapping("/coupon-preview")
    public ResponseEntity<PreviewCouponUseCase.Preview> previewCoupon(
            @Valid @RequestBody MultiItemOrderRequest request) {
        // 남의 쿠폰 보유 여부를 캐볼 수 없게 본인(또는 관리자)만 조회한다.
        ResourceOwnership.requireSelfOrAdmin(request.userId());
        List<CreateMultiItemOrderUseCase.Line> lines = request.lines().stream()
                .map(l -> new CreateMultiItemOrderUseCase.Line(l.productId(), l.variantId(), l.quantity()))
                .toList();
        return ResponseEntity.ok(
                previewCouponUseCase.preview(request.userId(), request.couponCode(), lines));
    }

    /**
     * 요청의 동의 목록을 서비스가 받는 형태로 옮긴다.
     *
     * <p>동의 시각과 접속지는 <b>요청 본문에서 받지 않는다</b>. 증명하려는 사실을 증명 대상이 스스로
     * 적게 하면 증명이 아니기 때문이다. 접속지는 이미 {@code AuditContextFilter} 가 요청마다 뽑아
     * 둔 값을 그대로 쓴다 — 여기서 헤더를 다시 파싱하면 같은 규칙이 두 자리에 생기고, 한쪽만
     * 고쳐지는 날이 온다. 시각은 서비스가 {@code Clock} 으로 찍는다.
     *
     * <p>{@code null} 을 그대로 넘기지 않는 것이 중요하다. 이 경로는 동의를 <b>받는</b> 경로이므로,
     * 아무것도 안 왔으면 필수 항목 누락으로 거절돼야 한다. null 로 넘기면 "동의를 받지 않는 경로"가
     * 되어 조용히 통과한다.
     */
    private static CreateMultiItemOrderUseCase.ConsentSubmission toConsentSubmission(
            List<ConsentRequest> consents) {
        List<RecordOrderConsentUseCase.Acceptance> acceptances =
                consents == null ? List.of()
                        : consents.stream()
                        .map(c -> new RecordOrderConsentUseCase.Acceptance(
                                c.termsCode(), c.termsVersion(), c.agreed()))
                        .toList();
        return new CreateMultiItemOrderUseCase.ConsentSubmission(
                acceptances, AuditContext.get().ipAddress());
    }

    public record MultiItemOrderRequest(
            @jakarta.validation.constraints.NotNull Long userId,
            @jakarta.validation.constraints.NotEmpty List<LineRequest> lines,
            String couponCode,
            @Valid ShippingAddressRequest shippingAddress,
            @Valid List<ConsentRequest> consents) {}

    /**
     * 결제 화면에서 올라온 동의 체크 하나.
     *
     * <p>버전을 함께 보내는 것이 핵심이다. 코드만 보내면 서버는 "지금 문안에 동의했다"고 기록하는데,
     * 사용자가 읽은 것은 화면을 열던 때의 문안이다. 그 사이에 문안이 바뀌었으면 기록이 거짓이 된다.
     */
    public record ConsentRequest(
            @jakarta.validation.constraints.NotBlank String termsCode,
            @jakarta.validation.constraints.NotNull Integer termsVersion,
            boolean agreed) {}

    public record LineRequest(
            @jakarta.validation.constraints.NotNull Long productId,
            Long variantId,
            @jakarta.validation.constraints.Min(1) int quantity) {}

    /**
     * 주문 시점 배송지. 주문 생성(POST /orders/multi)에서는 필수이고, 같은 레코드를 쓰는
     * 쿠폰 미리보기에서는 보내지 않는다.
     */
    public record ShippingAddressRequest(
            @jakarta.validation.constraints.NotBlank String recipientName,
            @jakarta.validation.constraints.NotBlank String phone,
            @jakarta.validation.constraints.NotBlank String postalCode,
            @jakarta.validation.constraints.NotBlank String address1,
            String address2,
            String deliveryMemo) {

        github.lms.lemuel.order.domain.ShippingAddressSnapshot toSnapshot() {
            return new github.lms.lemuel.order.domain.ShippingAddressSnapshot(
                    recipientName, phone, postalCode, address1, address2, deliveryMemo);
        }
    }

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

    /**
     * 관리자 주문 목록 — 페이지.
     *
     * <p>이 자리에 있던 {@code GET /orders/admin/all} 은 전 주문을 한 응답에 실어 보냈다.
     * 주문은 지우지 않고 계속 쌓이므로 그 API 는 반드시 느려지다 죽는데, 죽기 전까지는 잘
     * 도는 것처럼 보인다. 대신 화면이 한 화면치만 가져가고, 전체 규모는 아래 요약이 말한다.
     *
     * <p>{@code status} 는 <b>반복 가능</b>하다({@code ?status=A&status=B}). 승인 큐가 두 상태를
     * 한 화면에서 보기 때문인데, 전건을 받아 클라이언트가 걸러내던 방식은 페이징이 붙는 순간
     * 대기 건을 조용히 빠뜨린다.
     */
    @Operation(summary = "주문 목록 조회 (관리자)",
            description = "상태·기간으로 거른 주문을 최신순 페이지로 조회한다. status 는 반복 지정할 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/admin")
    public ResponseEntity<AdminOrderPageResponse> searchOrders(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        SearchOrdersUseCase.OrderPage result = searchOrdersUseCase.search(
                new SearchOrdersUseCase.OrderQuery(
                        status == null ? List.of() : status, from, to, page, size));

        return ResponseEntity.ok(new AdminOrderPageResponse(
                result.content().stream().map(OrderResponse::from).collect(Collectors.toList()),
                result.page(), result.size(), result.totalElements(), result.totalPages()));
    }

    /**
     * 관리자 주문 요약 — 같은 조건의 상태별 건수·금액 합계.
     *
     * <p>목록과 <b>반드시 짝으로</b> 쓴다. 대시보드의 "총 주문"·"매출"·상태 분포를 목록 배열에서
     * 세면 페이징이 붙은 순간 그 숫자는 "첫 페이지만 센 값"이 되는데, 화면에는 여전히 숫자가
     * 찍히고 틀렸다고 말해 주는 것이 없다.
     */
    @Operation(summary = "주문 요약 (관리자)", description = "같은 조건의 상태별 건수와 금액 합계. 페이지에 잘리지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/admin/summary")
    public ResponseEntity<AdminOrderSummaryResponse> orderSummary(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        List<SearchOrdersUseCase.OrderStatusCount> counts = searchOrdersUseCase.countByStatus(
                new SearchOrdersUseCase.OrderQuery(
                        status == null ? List.of() : status, from, to, 0, 1));

        long totalCount = counts.stream().mapToLong(SearchOrdersUseCase.OrderStatusCount::count).sum();
        java.math.BigDecimal totalAmount = counts.stream()
                .map(SearchOrdersUseCase.OrderStatusCount::amountSum)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        return ResponseEntity.ok(new AdminOrderSummaryResponse(totalCount, totalAmount, counts.stream()
                .map(c -> new AdminOrderStatusCountResponse(c.status(), c.count(), c.amountSum()))
                .collect(Collectors.toList())));
    }

    /** 관리자 주문 목록 한 페이지. */
    public record AdminOrderPageResponse(
            List<OrderResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    /**
     * 관리자 주문 요약.
     *
     * <p>{@code totalAmount} 는 상태를 가리지 않은 <b>금액 총합</b>이다. "매출"이 아니다 —
     * 취소·환불 건도 포함한다. 무엇을 매출로 볼지는 화면의 정책이라 서버가 미리 정하지 않고,
     * 상태별 값을 그대로 함께 준다.
     */
    public record AdminOrderSummaryResponse(
            long totalCount,
            java.math.BigDecimal totalAmount,
            List<AdminOrderStatusCountResponse> statuses) {}

    /** 상태 한 줄. {@code status} 는 DB 에 적힌 값 그대로다. */
    public record AdminOrderStatusCountResponse(
            String status,
            long count,
            java.math.BigDecimal amountSum) {}

    @Operation(summary = "주문 취소", description = "주문 상태를 CANCELED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "취소할 수 없는 상태")
    })
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @Parameter(description = "주문 ID", required = true) @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long id) {
        requireOwner(id);
        Order order = changeOrderStatusUseCase.cancelOrder(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "주문 취소 신청", description = "사용자가 주문 취소를 신청한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신청 성공"),
            @ApiResponse(responseCode = "403", description = "타인 주문"),
            @ApiResponse(responseCode = "409", description = "취소를 신청할 수 없는 상태")
    })
    @PostMapping("/{id}/cancellation-request")
    public ResponseEntity<OrderResponse> requestCancellation(
            @PathVariable Long id,
            @RequestBody StatusReasonRequest request,
            Principal principal) {
        requireOwner(id);
        Order order = changeOrderStatusUseCase.requestCancellation(id, request.reason(), actor(principal));
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "환불 신청", description = "사용자가 결제 완료 주문의 환불을 신청한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신청 성공"),
            @ApiResponse(responseCode = "403", description = "타인 주문"),
            @ApiResponse(responseCode = "409", description = "환불을 신청할 수 없는 상태")
    })
    @PostMapping("/{id}/refund-request")
    public ResponseEntity<OrderResponse> requestRefund(
            @PathVariable Long id,
            @RequestBody StatusReasonRequest request,
            Principal principal) {
        requireOwner(id);
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
        requireOwner(id);
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
        requireOwner(id);
        return ResponseEntity.ok(cancelOrderItemsUseCase.cancelItems(
                id, request.itemIds(), request.reason(), actor(principal)));
    }

    /**
     * 주문 단건을 대상으로 하는 사용자 조작의 IDOR 방어 — 경로 변수의 주문 번호가 <b>내 주문</b>인지
     * JWT 주체와 대조한다. ADMIN·MANAGER 는 CS 처리를 위해 통과한다.
     *
     * <p>이 저장소의 인가는 {@code SecurityConfig} 의 URL 매처가 전부인데, 매처는 역할만 볼 뿐
     * "이 {@code {id}} 가 누구 것인가"를 가르지 못한다. 그래서 신청·취소처럼 주문 번호 하나로
     * 남의 주문을 건드릴 수 있는 경로는 컨트롤러가 직접 대조해야 한다.
     *
     * <p>빠뜨렸을 때의 실제 피해가 "남이 내 환불을 대신 신청해 준다" 정도로 끝나지 않는다는 점이
     * 중요하다. 전이표상 {@code REFUND_REQUESTED} 에서 갈 수 있는 곳은 {@code REFUNDED} 뿐이라,
     * 아무 주문에나 환불 신청을 밀어 넣으면 그 주문의 배송이 운영자가 손대기 전까지 멈춘다.
     */
    private void requireOwner(Long orderId) {
        ResourceOwnership.requireSelfOrAdmin(getOrderUseCase.getOrderById(orderId).getUserId());
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
