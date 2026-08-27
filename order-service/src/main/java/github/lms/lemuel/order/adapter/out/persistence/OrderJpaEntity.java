package github.lms.lemuel.order.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order JPA Entity (인프라 레이어, 도메인과 분리)
 * DB 스키마: id, user_id, amount, status, created_at, updated_at
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "shipping_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "shipped", nullable = false)
    private boolean shipped;

    /** 재고 원복 완료 — 취소/환불/반품 회수 경로의 이중 원복을 막는 멱등 플래그. */
    @Column(name = "stock_restored", nullable = false)
    private boolean stockRestored;

    /*
     * 주문 시점 배송지 스냅샷 — 전부 nullable 이다. 스냅샷 도입 이전 주문에는 값이 없고,
     * 배송지 변경은 shipments 쪽에만 반영되므로 이 컬럼들은 주문 시점 값으로 고정된다.
     */
    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", length = 30)
    private String recipientPhone;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(name = "address1", length = 200)
    private String address1;

    @Column(name = "address2", length = 200)
    private String address2;

    @Column(name = "delivery_memo", length = 500)
    private String deliveryMemo;

    // 여러 곳 배송 묶음 id(UUID). 한 번의 결제에서 나온 주문들이 같은 값을 갖고, 단일 배송지 주문은 비어 있다.
    @Column(name = "destination_group_id", length = 36)
    private String destinationGroupId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "CREATED";
        }
        if (shippingFee == null) {
            shippingFee = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
