package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 분할결제의 한 라인 — Payment 의 자식 도메인.
 *
 * <p>예) 50,000원 결제 = [PaymentTender(POINT, 5,000원), PaymentTender(GIFT_CARD, 10,000원),
 * PaymentTender(CARD, 35,000원)]. 모든 tender 의 amount 합계는 payment.amount 와 정확히 일치.
 *
 * <p>환불 정책: tender 별 잔여 환불 가능 금액(amount - refundedAmount)을 추적. 환불은
 * 일반적으로 sequence 역순 (외부 PG 먼저 → 내부 잔액 마지막) 으로 처리.
 */
public class PaymentTender {

    private Long id;
    private Long paymentId;
    private final TenderType type;
    private final BigDecimal amount;
    private BigDecimal refundedAmount;
    private String pgTransactionId;
    private TenderStatus status;
    private final int sequence;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaymentTender newTender(TenderType type, BigDecimal amount, int sequence) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new PaymentInvariantViolationException("tender amount 는 양수여야 합니다");
        }
        if (sequence <= 0) {
            throw new PaymentInvariantViolationException("sequence 는 1 이상");
        }
        LocalDateTime now = LocalDateTime.now();
        return new PaymentTender(null, null, type, amount, BigDecimal.ZERO, null,
                TenderStatus.PENDING, sequence, now, now);
    }

    public static PaymentTender rehydrate(Long id, Long paymentId, TenderType type,
                                           BigDecimal amount, BigDecimal refundedAmount,
                                           String pgTransactionId, TenderStatus status,
                                           int sequence, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new PaymentTender(id, paymentId, type, amount, refundedAmount,
                pgTransactionId, status, sequence, createdAt, updatedAt);
    }

    private PaymentTender(Long id, Long paymentId, TenderType type, BigDecimal amount,
                          BigDecimal refundedAmount, String pgTransactionId, TenderStatus status,
                          int sequence, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.type = type;
        this.amount = amount;
        this.refundedAmount = refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
        this.pgTransactionId = pgTransactionId;
        this.status = status;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void authorize(String pgTransactionId) {
        if (status != TenderStatus.PENDING) {
            throw new InvalidPaymentStateException("PENDING 에서만 AUTHORIZED 전이: " + status);
        }
        // POINT / GIFT_CARD 는 외부 PG 미호출 → pgTransactionId null 허용
        if (type.usesExternalPg() && (pgTransactionId == null || pgTransactionId.isBlank())) {
            throw new PaymentInvariantViolationException("외부 PG tender 는 pgTransactionId 필수: " + type);
        }
        this.pgTransactionId = pgTransactionId;
        this.status = TenderStatus.AUTHORIZED;
        touch();
    }

    public void capture() {
        if (status != TenderStatus.AUTHORIZED && status != TenderStatus.PENDING) {
            throw new InvalidPaymentStateException("AUTHORIZED/PENDING 에서만 CAPTURED 전이: " + status);
        }
        this.status = TenderStatus.CAPTURED;
        touch();
    }

    /**
     * 부분/전체 환불. 잔여 환불 가능 금액 초과 시 {@link PaymentInvariantViolationException}.
     */
    public void addRefund(BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw new PaymentInvariantViolationException("환불 금액은 양수여야 합니다");
        }
        if (status != TenderStatus.CAPTURED) {
            throw new InvalidPaymentStateException("CAPTURED 상태에서만 환불 가능: " + status);
        }
        BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(this.amount) > 0) {
            throw new PaymentInvariantViolationException(
                    "환불 가능액 초과: 요청=" + refundAmount + ", 잔여=" + getRefundableAmount());
        }
        this.refundedAmount = newRefunded;
        if (this.refundedAmount.compareTo(this.amount) == 0) {
            this.status = TenderStatus.REFUNDED;
        }
        touch();
    }

    public BigDecimal getRefundableAmount() {
        return amount.subtract(refundedAmount);
    }

    public boolean isFullyRefunded() {
        return refundedAmount.compareTo(amount) >= 0;
    }

    public void attachToPayment(Long paymentId) {
        this.paymentId = paymentId;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1회만 부여");
        this.id = id;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public TenderType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public String getPgTransactionId() { return pgTransactionId; }
    public TenderStatus getStatus() { return status; }
    public int getSequence() { return sequence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
