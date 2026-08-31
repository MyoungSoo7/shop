package github.lms.lemuel.seller.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** {@code seller_orders} 매핑 — 결제 행에 상품·상태를 붙여 주는 보조 프로젝션. */
@Entity
@Table(name = "seller_orders")
class SellerOrderJpaEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SellerOrderJpaEntity() {
    }
}
