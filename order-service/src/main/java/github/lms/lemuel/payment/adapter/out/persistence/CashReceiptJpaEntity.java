package github.lms.lemuel.payment.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 현금영수증 영속 엔티티.
 *
 * <p>"결제 1 건당 유효 1 건" 제약은 여기 선언하지 않는다 — 실패·취소 건은 자리를 비워야 하므로
 * {@code WHERE status IN ('REQUESTED','ISSUED','CANCEL_REQUESTED')} 조건이 붙은 부분 UNIQUE
 * 인덱스여야 하고(마이그레이션 {@code V20260821223700}), JPA 애노테이션으로는 표현할 수 없다.
 */
@Entity
@Table(name = "cash_receipts")
@Getter
@Setter
@NoArgsConstructor
public class CashReceiptJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "purpose", nullable = false, length = 20)
    private String purpose;

    @Column(name = "identifier_type", nullable = false, length = 20)
    private String identifierType;

    /** 정규화된 숫자열. 응답에는 절대 원문을 싣지 않는다(마스킹은 도메인 VO 가 한다). */
    @Column(name = "identifier_value", nullable = false, length = 32)
    private String identifierValue;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "supply_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal supplyAmount;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "approval_number", length = 40)
    private String approvalNumber;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) requestedAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }
}
