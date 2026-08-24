package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Order Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 *
 * <p>두 가지 생성 경로:
 * <ul>
 *   <li>{@link #create(Long, Long, BigDecimal)} — 단건 주문 (레거시 호환). productId 단일.</li>
 *   <li>{@link #createMultiItem(Long, List)} — 다건 주문. productId NULL, items 가 진실의 원천.</li>
 * </ul>
 *
 * <p>amount 는 다건 주문에서 {@code (모든 line_amount 의 합) - 쿠폰 할인} 으로 자동 계산되어
 * 도메인 불변식 (영수증 ↔ 결제 ↔ 정산 금액 일치) 을 보장한다. 쿠폰 없는 주문은 할인 0 이므로
 * amount = line_amount 합.
 */
public class Order {

    private Long id;
    private final Long userId;
    private final Long productId;
    private final BigDecimal amount;
    private OrderStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private BigDecimal shippingFee = BigDecimal.ZERO;  // 결제에 포함된 배송비(기본 0). 환불 정책 계산에 사용.
    private boolean shipped = false;                   // 배송 시작(IN_TRANSIT/DELIVERED 도달) 여부 — 상태 전이와 무관하게 보존.
    private boolean stockRestored = false;             // 재고 원복 완료 — 이중 원복 방지(멱등 플래그).
    private final List<OrderItem> items = new ArrayList<>();

    // 정본 생성자 — 생성/복원 팩토리(create/createMultiItem/rehydrate)만 통과(Settlement 와 동형).
    // 불변 식별·금액 필드(userId·productId·amount·createdAt)를 여기서 못박아 재할당을 컴파일 단에서 봉인하고,
    // 외부의 임의 status 주입도 함께 봉인한다.
    private Order(Long id, Long userId, Long productId, BigDecimal amount, OrderStatus status,
                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.productId = productId;
        this.amount = amount;
        this.status = status != null ? status : OrderStatus.CREATED;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Order create(Long userId, Long productId, BigDecimal amount) {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order(null, userId, productId, amount, OrderStatus.CREATED, now, now);
        order.validateUserId();
        order.validateAmount();
        return order;
    }

    public static Order create(Long userId, BigDecimal amount) {
        return create(userId, 1L, amount); // 기본 productId를 1로 지정
    }

    /**
     * 다건 주문 팩토리.
     *
     * <p>amount 는 모든 OrderItem.lineAmount 의 합으로 자동 계산되며,
     * productId 는 null 로 두어 "이 주문은 다건이다" 라는 의미를 부여한다.
     * 외부에서 amount 를 수동 지정할 수 없어 영수증/결제/정산 금액 정합성이 도메인 차원에서 보장된다.
     */
    public static Order createMultiItem(Long userId, List<OrderItem> items) {
        return createMultiItem(userId, items, BigDecimal.ZERO);
    }

    /**
     * 다건 주문 팩토리 (쿠폰 할인 반영).
     *
     * <p>소계(subtotal) = 모든 {@link OrderItem#getLineAmount()} 의 합. 최종 결제 금액
     * {@code amount = subtotal - discountAmount} 로, 외부에서 amount 를 임의 지정할 수 없어
     * 영수증/결제/정산 정합성이 도메인 차원에서 보장된다.
     *
     * <p>할인 금액은 별도 컬럼으로 저장하지 않아도 subtotal(= 영속된 {@code order_items.line_amount}
     * 합) 에서 {@code discount = subtotal - amount} 로 항상 역산할 수 있으므로 스키마 확장이 필요 없다.
     * 쿠폰-주문의 연결 자체는 {@code coupon_usages.order_id} 가 보존한다.
     *
     * @param discountAmount 쿠폰 할인 금액(없으면 {@code null}/0). 0 이상이어야 하고, 결제 금액은
     *                       0 보다 커야 하므로 subtotal 미만이어야 한다.
     */
    public static Order createMultiItem(Long userId, List<OrderItem> items, BigDecimal discountAmount) {
        return createMultiItem(userId, items, discountAmount, BigDecimal.ZERO);
    }

    /**
     * 다건 주문 팩토리 (쿠폰 할인 + 배송비 반영) — 결제 금액이 확정되는 유일한 지점.
     *
     * <p>{@code amount = subtotal - discount + shippingFee}. 결제는 {@code order.amount} 로
     * 만들어지므로(CreatePaymentUseCase) 배송비를 amount 밖에 두면 고객에게 청구되지 않고, 반대로
     * amount 에만 더하고 {@code shippingFee} 를 비워 두면 배송 후 환불에서 배송비를 되돌려주게 된다
     * ({@link RefundPolicy} 가 이 필드로 차감액을 계산한다). 둘을 한 호출에서 함께 못박는 이유다.
     *
     * <p>할인 상한은 <b>배송비를 뺀 소계</b>다 — 배송비를 더해 총액이 양수가 되더라도 상품 대금이
     * 0 이하인 주문(= 배송비만 결제하는 주문)은 만들지 않는다.
     *
     * @param shippingFee 산정된 배송비(없으면 {@code null}/0). 음수 불가.
     */
    public static Order createMultiItem(Long userId, List<OrderItem> items,
                                        BigDecimal discountAmount, BigDecimal shippingFee) {
        if (items == null || items.isEmpty()) {
            throw new OrderInvariantViolationException("다건 주문은 최소 1 개 이상의 아이템이 필요합니다");
        }
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        if (discount.signum() < 0) {
            throw new OrderInvariantViolationException("할인 금액은 음수일 수 없습니다");
        }
        // 라운딩 정책 경계 — 공용 Money VO(shared-common)를 여기서는 의도적으로 쓰지 않는다.
        // line_amount = unitPrice(scale 0 정수 KRW) × quantity(int) 이고 할인도 정수라 이 합산·차감은
        // 항상 정확한 정수 연산이다: 반올림 여지가 없어 Money 의 scale 2 HALF_UP 정규화 이득이 0 이다.
        // 반대로 Money 를 통과시키면 amount 가 scale 2(예: 3088000.00)로 바뀌어, 이 금액이 흘러가는
        // 결제·정산 프로젝션의 금액 비교(MSA 경계)에 scale drift 만 유발한다. Money javadoc 의
        // "scale 2 HALF_UP 통화 전용" 경계와 일치하는 판단 — 정수 주문 총액은 raw BigDecimal 로 둔다.
        BigDecimal subtotal = items.stream()
                .map(OrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add); // 정수 KRW 정확 합산 — Money 미적용(아래 경계 주석)
        if (discount.compareTo(subtotal) >= 0) {
            throw new OrderInvariantViolationException(
                    "할인 금액(" + discount + ") 이 주문 소계(" + subtotal + ") 이상일 수 없습니다");
        }
        BigDecimal shipping = shippingFee != null ? shippingFee : BigDecimal.ZERO;
        if (shipping.signum() < 0) {
            throw new OrderInvariantViolationException("배송비는 음수일 수 없습니다: " + shipping);
        }
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order(null, userId, null, subtotal.subtract(discount).add(shipping),
                OrderStatus.CREATED, now, now);
        order.shippingFee = shipping;
        order.validateUserId();
        order.validateAmount();
        order.items.addAll(items);
        return order;
    }

    // 도메인 규칙: userId 검증
    private void validateUserId() {
        if (userId == null || userId <= 0) {
            throw new OrderInvariantViolationException("User ID must be a positive number");
        }
    }

    // 도메인 규칙: amount 검증
    private void validateAmount() {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OrderInvariantViolationException("Amount must be greater than zero");
        }
    }

    /**
     * 상태머신 가드 전이. {@link OrderStatus#canTransitionTo(OrderStatus)} 규칙에 어긋나면 예외.
     * 배송·취소·환불 다단계 전이(서비스 계층)와 타 컨텍스트(payment) 의 상태 변경 요청이 모두 이 경로를 거친다.
     * 동일 상태로의 재적용은 멱등 처리(no-op)한다.
     */
    public void transitionTo(OrderStatus target) {
        if (target == null) {
            throw new OrderInvariantViolationException("target status required");
        }
        if (this.status == target) {
            return; // 멱등: 동일 상태 재적용 무시
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidOrderStateException(this.status, target);
        }
        this.status = target;
        // 배송이 한 번이라도 시작되면(IN_TRANSIT/DELIVERED) 기록을 남긴다 — 이후 환불 신청으로
        // 상태가 REFUND_REQUESTED 로 바뀌어도 "배송 시작됨" 사실은 유지되어 환불 정책이 배송비를 차감한다.
        if (target == OrderStatus.IN_TRANSIT || target == OrderStatus.DELIVERED) {
            this.shipped = true;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 주문 취소 — "결제 전 취소" 의 좁은 의미(CREATED 만).
    // 전이표 canTransitionTo(CANCELED) 는 CANCELLATION_REQUESTED/APPROVED 도 허용하나(취소승인 흐름은
    // 서비스가 transitionTo 로 처리), 이 메서드의 의미는 결제 전 취소로 한정되므로 isCancelable() 로 위임한다.
    public void cancel() {
        if (!isCancelable()) {
            throw new InvalidOrderStateException(this.status, OrderStatus.CANCELED);
        }
        this.status = OrderStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 주문 완료 (결제 완료) — 허용 전이는 전이표 canTransitionTo(PAID) 단일 출처에 위임한다
    // (CREATED 만 PAID 로 전이 가능 — 인라인 가드와 동형).
    public void complete() {
        if (!this.status.canTransitionTo(OrderStatus.PAID)) {
            throw new InvalidOrderStateException(this.status, OrderStatus.PAID);
        }
        this.status = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 환불 처리 — "PAID 에서의 단순 환불" 의 좁은 의미(PAID 만).
    // 전이표 canTransitionTo(REFUNDED) 는 배송단계·취소승인에서의 환불도 허용하나(그 경로는 서비스가
    // transitionTo 로 처리), 이 메서드의 의미는 PAID 직접 환불로 한정되므로 isRefundable() 로 위임한다.
    public void refund() {
        if (!isRefundable()) {
            throw new InvalidOrderStateException(this.status, OrderStatus.REFUNDED);
        }
        this.status = OrderStatus.REFUNDED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 라인 단위 부분 취소 — 지정한 라인을 취소 상태로 바꾸고 <b>취소된 라인 금액 합</b>을 돌려준다.
     *
     * <p>주문 총액({@link #getAmount()})은 발행된 영수증이라 여기서 바뀌지 않는다. 얼마를 실제로
     * 되돌려줬는지는 결제의 {@code refundedAmount} 가 들고 있고, 이 메서드는 "어떤 라인이 살아
     * 있는가"만 확정한다. 그 살아남은 라인이 배송비 재산정의 입력이 된다 — 무료배송 임계를 채우던
     * 상품이 빠지면 면제됐던 배송비가 되살아난다(실무 커머스의 배송비 재부과 규칙).
     *
     * <p><b>배송 시작 후에는 거절</b>한다. 이미 출고된 물건을 "취소"로 처리하면 재고가 장부에만
     * 돌아오고 실물은 고객에게 있다 — 그 경로는 반품(회수 확인 후 원복)이다.
     *
     * @param itemIds 취소할 라인 id 목록(비어 있으면 거절, 주문에 없는 id·이미 취소된 id 도 거절)
     * @return 취소된 라인들의 {@code lineAmount} 합
     */
    public BigDecimal cancelItems(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new OrderInvariantViolationException("취소할 주문 라인을 지정해야 합니다");
        }
        if (!isMultiItem()) {
            throw new OrderInvariantViolationException("라인이 없는 주문은 부분 취소 대상이 아닙니다");
        }
        if (!isItemCancelable()) {
            throw new InvalidOrderStateException(this.status, OrderStatus.CANCELED);
        }

        List<OrderItem> targets = new ArrayList<>(itemIds.size());
        for (Long itemId : itemIds) {
            OrderItem target = items.stream()
                    .filter(item -> itemId.equals(item.getId()))
                    .findFirst()
                    .orElseThrow(() -> new OrderInvariantViolationException(
                            "이 주문에 없는 라인입니다: itemId=" + itemId));
            targets.add(target);
        }

        // 전량 검증 후 일괄 취소 — 중간 라인에서 실패하면 앞 라인만 취소된 반쪽 상태가 남는다.
        BigDecimal canceledSubtotal = BigDecimal.ZERO;
        for (OrderItem target : targets) {
            if (target.isCanceled()) {
                throw new OrderInvariantViolationException(
                        "이미 취소된 주문 라인입니다: itemId=" + target.getId());
            }
        }
        for (OrderItem target : targets) {
            target.cancel();
            canceledSubtotal = canceledSubtotal.add(target.getLineAmount());
        }
        this.updatedAt = LocalDateTime.now();
        return canceledSubtotal;
    }

    /** 아직 취소되지 않은 라인 — 배송비 재산정·출고 대상의 진실의 원천. */
    public List<OrderItem> activeItems() {
        return items.stream().filter(item -> !item.isCanceled()).toList();
    }

    /** 라인이 하나도 남지 않았는지(= 주문 전체가 취소된 것과 같은지). */
    public boolean allItemsCanceled() {
        return isMultiItem() && activeItems().isEmpty();
    }

    /**
     * 취소·환불 <b>신청 철회</b> — 신청 상태에서 신청 직전 상태로 되돌린다.
     *
     * <p>신청 상태(CANCELLATION_REQUESTED / REFUND_REQUESTED)에서 나가는 길이 승인뿐이면, 마음이
     * 바뀐 고객의 주문은 운영자가 처리할 때까지 묶인다. 철회는 그 막다른 길을 여는 정상 경로다.
     *
     * <p>되돌아갈 상태를 임의로 받지 않는다 — <b>그 신청을 낼 수 있었던 상태</b>여야 한다
     * ({@code restoreTo.canTransitionTo(현재 상태)}). 이 한 줄 덕에 "배송 중이던 주문의 환불
     * 신청을 철회하면 배송 중으로 돌아간다"가 자동으로 성립하고, 결제된 적 없는 주문이 PAID 로
     * 승격되는 경로는 열리지 않는다. 전이표를 역방향으로 확장하지 않는 이유이기도 하다 —
     * 되돌리기는 이 메서드 하나로만 가능하다.
     *
     * @param restoreTo 신청 직전 상태(호출자가 상태 이력에서 읽어 온다)
     */
    public void withdrawRequest(OrderStatus restoreTo) {
        if (this.status != OrderStatus.CANCELLATION_REQUESTED
                && this.status != OrderStatus.REFUND_REQUESTED) {
            throw new InvalidOrderStateException(this.status, "철회할 신청이 없습니다");
        }
        if (restoreTo == null) {
            throw new OrderInvariantViolationException("철회 후 복귀할 상태를 지정해야 합니다");
        }
        if (!restoreTo.canTransitionTo(this.status)) {
            throw new OrderInvariantViolationException(
                    "이 신청을 낼 수 없었던 상태로는 되돌릴 수 없습니다: " + restoreTo + " → " + this.status);
        }
        this.status = restoreTo;
        this.updatedAt = LocalDateTime.now();
    }

    /** 라인 단위 취소가 허용되는 단계인지 — 출고 전까지만. */
    public boolean isItemCancelable() {
        return this.status == OrderStatus.CREATED
                || this.status == OrderStatus.PAID
                || this.status == OrderStatus.SHIPPING_PENDING;
    }

    public boolean isCancelable() {
        return this.status == OrderStatus.CREATED;
    }

    public boolean isRefundable() {
        return this.status == OrderStatus.PAID;
    }

    /**
     * 영속 레코드 복원 팩토리. 매퍼가 no-arg + setter 대신 이 경로로만 도메인을 재구성해
     * 상태 전이 규칙을 우회하는 임의 status 주입을 봉인한다. items 는 별도 로드되어 replaceItems 로 부착.
     */
    public static Order rehydrate(Long id, Long userId, Long productId, BigDecimal amount,
                                  OrderStatus status, LocalDateTime createdAt, LocalDateTime updatedAt,
                                  BigDecimal shippingFee, boolean shipped) {
        Order order = new Order(id, userId, productId, amount, status, createdAt, updatedAt);
        order.shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        order.shipped = shipped;
        return order;
    }

    public static Order rehydrate(Long id, Long userId, Long productId, BigDecimal amount,
                                  OrderStatus status, LocalDateTime createdAt, LocalDateTime updatedAt,
                                  BigDecimal shippingFee, boolean shipped, boolean stockRestored) {
        Order order = rehydrate(id, userId, productId, amount, status, createdAt, updatedAt, shippingFee, shipped);
        order.stockRestored = stockRestored;
        return order;
    }

    /**
     * 회수 대기 재고를 가진 주문인지 — 배송된 물건에 대해 환불·취소가 끝났는데 아직 물건이 돌아오지 않은 상태.
     *
     * <p>이 상태의 수량은 <b>어느 쪽에도 잡혀 있지 않다</b>: 판매 가능 재고로는 복귀하지 않았고(회수 미확인),
     * 고객에게는 이미 환불됐다. 방치하면 팔 수 있는 물건이 영영 묶이므로 운영자가 추적해야 한다
     * (반품 회수가 확정되면 {@link #claimStockRestorationOnReturn()} 로 복귀).
     */
    public boolean isAwaitingStockReclaim() {
        return shipped
                && !stockRestored
                && !items.isEmpty()
                && (status == OrderStatus.REFUNDED || status == OrderStatus.CANCELED);
    }

    /** 이 주문의 재고가 이미 원복됐는지. */
    public boolean isStockRestored() {
        return stockRestored;
    }

    /**
     * 취소·환불에 따른 재고 원복 권한을 요청한다. 원복 대상 라인을 돌려주며, 한 번 나간 권한은
     * 다시 나오지 않는다(멱등) — 같은 주문이 두 번 원복되면 없는 재고가 생긴다.
     *
     * <p><b>배송이 시작된 주문은 원복하지 않는다.</b> 물건이 고객 손에 있는데 재고를 되돌리면
     * 장부재고가 실재고를 넘어 "팔았는데 물건이 없는" 초과판매가 난다. 그 물건은 실제 회수가
     * 확인될 때({@link #claimStockRestorationOnReturn()}) 비로소 재고로 돌아온다.
     *
     * @return 원복할 라인들. 빈 목록이면 원복 대상이 아니다(배송됨·이미 원복됨·라인 없는 레거시 주문)
     */
    public List<OrderItem> claimStockRestorationOnCancel() {
        if (shipped) {
            return List.of();
        }
        return claimStockRestoration();
    }

    /**
     * 반품 회수 완료에 따른 재고 원복 권한을 요청한다. 물건이 실제로 돌아온 것이 확인된 시점이므로
     * 배송된 주문도 원복 대상이다. 마찬가지로 권한은 한 번만 나간다 — 배송 전 취소로 이미 원복된
     * 주문이 반품 회수로 다시 원복되는 일은 없다.
     */
    public List<OrderItem> claimStockRestorationOnReturn() {
        return claimStockRestoration();
    }

    // 원복 권한 발급 — 미발급일 때만 라인을 넘기고 발급 사실을 기록한다.
    private List<OrderItem> claimStockRestoration() {
        if (stockRestored || items.isEmpty()) {
            return List.of();
        }
        stockRestored = true;
        this.updatedAt = LocalDateTime.now();
        return List.copyOf(items);
    }

    /**
     * 영속 후 DB 가 부여한 PK 를 1회만 주입(write-once). setter 우회·재부여를 막는다
     * (Settlement#assignId 와 동일 인프라 가드 — 재부여는 프로그래밍 오류라 generic IllegalStateException).
     */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    /**
     * 결제에 포함된 배송비 확정(null 은 0 으로 방어). 환불 정책 계산의 입력값.
     */
    public void assignShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public boolean isShipped() {
        return shipped;
    }

    /**
     * 다건 주문 라인 아이템 (단건 주문은 빈 리스트).
     */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isMultiItem() {
        return !items.isEmpty();
    }

    /**
     * Persistence 어댑터에서 자식들에 PK 가 부여된 후 부모 id 를 자식에게 주입할 때 사용.
     */
    public void attachItemsToOrder() {
        if (this.id == null) {
            throw new IllegalStateException("Order id 부여 후에만 호출 가능");
        }
        for (OrderItem item : items) {
            item.attachToOrder(this.id);
        }
    }

    /**
     * 영속 상태 복원 시 자식 아이템 채우기.
     */
    public void replaceItems(List<OrderItem> reloadedItems) {
        this.items.clear();
        if (reloadedItems != null) {
            this.items.addAll(reloadedItems);
        }
    }
}
