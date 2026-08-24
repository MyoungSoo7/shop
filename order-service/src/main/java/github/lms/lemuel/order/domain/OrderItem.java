package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 주문 라인 아이템 — Order 의 자식 도메인 객체.
 *
 * <p>특징:
 * <ul>
 *   <li>{@code productName}, {@code unitPrice} 는 <b>주문 시점 스냅샷</b>으로 영구 보관 —
 *       추후 상품 가격이 바뀌어도 영수증·정산 금액에는 영향 없음 (이력 보존)</li>
 *   <li>{@code variantId} 는 옵션 상품(SKU) 주문일 때만 채워진다. 옵션 없는 단일 상품은 null</li>
 *   <li>{@code lineAmount} = {@code unitPrice * quantity}. 도메인이 직접 계산해
 *       JPA Generated Column 의존을 없앤다 → 단위 테스트 용이</li>
 * </ul>
 */
public class OrderItem {

    private Long id;
    private Long orderId;
    private final Long productId;
    private final Long variantId;       // SKU 주문이면 채움, 아니면 null
    private final String sku;            // SKU 문자열 스냅샷 (감사용)
    private final String productName;    // 주문 시점 상품명
    private final BigDecimal unitPrice;  // 주문 시점 단가 (할인 적용 후)
    private final int quantity;
    private final BigDecimal lineAmount; // unitPrice * quantity
    private final LocalDateTime createdAt;
    private final List<OrderItemOption> options; // 주문 시점 옵션 선택 스냅샷 (옵션 없는 상품은 빈 목록)
    private LocalDateTime canceledAt;    // 부분 취소된 라인의 취소 시각. null 이면 살아 있는 라인.

    public static OrderItem newItem(Long productId, Long variantId, String sku,
                                     String productName, BigDecimal unitPrice, int quantity) {
        return newItem(productId, variantId, sku, productName, unitPrice, quantity, List.of());
    }

    /**
     * 옵션 스냅샷을 함께 담아 라인을 만든다.
     *
     * <p>같은 축이 두 번 들어오면 거절한다 — "빨강이면서 파랑" 인 주문 라인은 존재할 수 없고,
     * 이 불변식은 SKU 쪽 {@code (variant_id, product_option_axis_id)} PK 와 짝을 이룬다.
     */
    public static OrderItem newItem(Long productId, Long variantId, String sku,
                                     String productName, BigDecimal unitPrice, int quantity,
                                     List<OrderItemOption> options) {
        Objects.requireNonNull(productId, "productId");
        if (productName == null || productName.isBlank()) {
            throw new OrderInvariantViolationException("productName 은 필수");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new OrderInvariantViolationException("unitPrice 는 0 이상");
        }
        if (quantity <= 0) {
            throw new OrderInvariantViolationException("quantity 는 양수");
        }
        List<OrderItemOption> safeOptions = options == null ? List.of() : List.copyOf(options);
        Set<Integer> depths = new HashSet<>();
        for (OrderItemOption option : safeOptions) {
            if (!depths.add(option.getAxisSortOrder())) {
                throw new OrderInvariantViolationException(
                        "같은 옵션 차수가 두 번 들어왔습니다: " + option.getAxisSortOrder());
            }
        }
        BigDecimal line = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return new OrderItem(null, null, productId, variantId, sku, productName,
                unitPrice, quantity, line, LocalDateTime.now(), safeOptions);
    }

    public static OrderItem rehydrate(Long id, Long orderId, Long productId, Long variantId,
                                       String sku, String productName, BigDecimal unitPrice,
                                       int quantity, BigDecimal lineAmount, LocalDateTime createdAt) {
        return rehydrate(id, orderId, productId, variantId, sku, productName, unitPrice,
                quantity, lineAmount, createdAt, List.of());
    }

    public static OrderItem rehydrate(Long id, Long orderId, Long productId, Long variantId,
                                       String sku, String productName, BigDecimal unitPrice,
                                       int quantity, BigDecimal lineAmount, LocalDateTime createdAt,
                                       List<OrderItemOption> options) {
        return rehydrate(id, orderId, productId, variantId, sku, productName, unitPrice,
                quantity, lineAmount, createdAt, options, null);
    }

    /** 취소 시각까지 복원하는 팩토리 — 재기동 후에도 취소된 라인이 활성으로 되살아나지 않는다. */
    public static OrderItem rehydrate(Long id, Long orderId, Long productId, Long variantId,
                                       String sku, String productName, BigDecimal unitPrice,
                                       int quantity, BigDecimal lineAmount, LocalDateTime createdAt,
                                       List<OrderItemOption> options, LocalDateTime canceledAt) {
        OrderItem item = new OrderItem(id, orderId, productId, variantId, sku, productName,
                unitPrice, quantity, lineAmount, createdAt,
                options == null ? List.of() : List.copyOf(options));
        item.canceledAt = canceledAt;
        return item;
    }

    private OrderItem(Long id, Long orderId, Long productId, Long variantId, String sku,
                      String productName, BigDecimal unitPrice, int quantity,
                      BigDecimal lineAmount, LocalDateTime createdAt,
                      List<OrderItemOption> options) {
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.variantId = variantId;
        this.sku = sku;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineAmount = lineAmount;
        this.createdAt = createdAt;
        this.options = options;
    }

    void attachToOrder(Long orderId) {
        if (this.orderId != null && !this.orderId.equals(orderId)) {
            throw new IllegalStateException("이미 다른 주문에 속한 아이템: " + this.orderId);
        }
        this.orderId = orderId;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여 가능");
        }
        this.id = id;
        options.forEach(option -> option.attachToItem(id));
    }

    /** 주문 시점 옵션 선택 스냅샷 (차수 순). 옵션 없는 상품은 빈 목록. */
    public List<OrderItemOption> getOptions() {
        return options.stream()
                .sorted(Comparator.comparingInt(OrderItemOption::getAxisSortOrder))
                .toList();
    }

    /** 주문서 표시용 — "색상: 빨강 / 사이즈: L". 옵션이 없으면 빈 문자열. */
    public String describeOptions() {
        return getOptions().stream()
                .map(OrderItemOption::describe)
                .collect(Collectors.joining(" / "));
    }

    /**
     * 이 라인을 취소 처리한다. 이미 취소된 라인의 재취소는 거절 — 그대로 통과시키면 같은 라인이
     * 두 번 환불·재고 복원되는 입구가 된다(멱등 no-op 이 아니라 오류다).
     */
    void cancel() {
        if (canceledAt != null) {
            throw new OrderInvariantViolationException(
                    "이미 취소된 주문 라인입니다: itemId=" + id);
        }
        this.canceledAt = LocalDateTime.now();
    }

    public boolean isCanceled() { return canceledAt != null; }

    public LocalDateTime getCanceledAt() { return canceledAt; }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public Long getVariantId() { return variantId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
