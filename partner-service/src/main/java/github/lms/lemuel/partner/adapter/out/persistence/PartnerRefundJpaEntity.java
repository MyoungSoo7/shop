package github.lms.lemuel.partner.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** {@code partner_refunds} 매핑. 결제 행과 FK 로 묶지 않는 이유는 V1 마이그레이션 주석 참조. */
@Entity
@Table(name = "partner_refunds")
@IdClass(PartnerRefundId.class)
class PartnerRefundJpaEntity {

    @Id
    @Column(name = "payment_id")
    private Long paymentId;

    @Id
    @Column(name = "refund_key", length = 64)
    private String refundKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refunded_total", precision = 19, scale = 2)
    private BigDecimal refundedTotal;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected PartnerRefundJpaEntity() {
    }
}
