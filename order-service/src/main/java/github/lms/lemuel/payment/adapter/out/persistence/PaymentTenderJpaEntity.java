package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.TenderStatus;
import github.lms.lemuel.payment.domain.TenderType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_tenders")
public class PaymentTenderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tender_type", nullable = false, length = 30)
    private TenderType tenderType;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "pg_transaction_id", length = 500)
    private String pgTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenderStatus status;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PaymentTenderJpaEntity() { }

    public PaymentTenderJpaEntity(Long id, Long paymentId, TenderType tenderType, BigDecimal amount,
                                   BigDecimal refundedAmount, String pgTransactionId, TenderStatus status,
                                   int sequence, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.tenderType = tenderType;
        this.amount = amount;
        this.refundedAmount = refundedAmount;
        this.pgTransactionId = pgTransactionId;
        this.status = status;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public TenderType getTenderType() { return tenderType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public String getPgTransactionId() { return pgTransactionId; }
    public TenderStatus getStatus() { return status; }
    public int getSequence() { return sequence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void applyState(BigDecimal refundedAmount, String pgTransactionId,
                            TenderStatus status, LocalDateTime updatedAt) {
        this.refundedAmount = refundedAmount;
        this.pgTransactionId = pgTransactionId;
        this.status = status;
        this.updatedAt = updatedAt;
    }
}
