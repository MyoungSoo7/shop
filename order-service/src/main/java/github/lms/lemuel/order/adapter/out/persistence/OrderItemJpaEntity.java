package github.lms.lemuel.order.adapter.out.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_items")
public class OrderItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(length = 64)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 라인 부분 취소 시각. NULL 이면 살아 있는 라인. */
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    protected OrderItemJpaEntity() { }

    public OrderItemJpaEntity(Long id, Long orderId, Long productId, Long variantId, String sku,
                              String productName, BigDecimal unitPrice, int quantity,
                              BigDecimal lineAmount, LocalDateTime createdAt) {
        this(id, orderId, productId, variantId, sku, productName, unitPrice, quantity,
                lineAmount, createdAt, null);
    }

    public OrderItemJpaEntity(Long id, Long orderId, Long productId, Long variantId, String sku,
                              String productName, BigDecimal unitPrice, int quantity,
                              BigDecimal lineAmount, LocalDateTime createdAt,
                              LocalDateTime canceledAt) {
        this.canceledAt = canceledAt;
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
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

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
    public LocalDateTime getCanceledAt() { return canceledAt; }

    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
