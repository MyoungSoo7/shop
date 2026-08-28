package github.lms.lemuel.partner.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * {@code partner_sales} 매핑.
 *
 * <p>{@code capturedAt} 이 {@link LocalDateTime} 인 것은 우연이 아니다 — 프로듀서가 존 없는
 * 로컬시각을 싣기 때문에 {@code OffsetDateTime} 으로 받으면 존을 붙이는 쪽에서 9시간이 조용히
 * 밀린다. 컬럼도 {@code TIMESTAMP WITHOUT TIME ZONE} 이고, 둘이 짝을 이뤄야 한다.
 */
@Entity
@Table(name = "partner_sales")
class PartnerSaleJpaEntity {

    @Id
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "seller_tier", length = 20)
    private String sellerTier;

    @Column(name = "settlement_cycle", length = 20)
    private String settlementCycle;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "captured_at_estimated", nullable = false)
    private boolean capturedAtEstimated;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PartnerSaleJpaEntity() {
    }
}
