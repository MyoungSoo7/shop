package github.lms.lemuel.payment.adapter.in.dto;

import github.lms.lemuel.payment.domain.PaymentDomain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Web DTO for payment response
 */
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private String status;
    private String paymentMethod;
    private String pgTransactionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PaymentResponse() {
    }

    public PaymentResponse(PaymentDomain paymentDomain) {
        this.id = paymentDomain.getId();
        this.orderId = paymentDomain.getOrderId();
        this.amount = paymentDomain.getAmount();
        this.status = paymentDomain.getStatus().name();
        this.paymentMethod = paymentDomain.getPaymentMethod();
        this.pgTransactionId = paymentDomain.getPgTransactionId();
        this.createdAt = paymentDomain.getCreatedAt();
        this.updatedAt = paymentDomain.getUpdatedAt();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPgTransactionId() {
        return pgTransactionId;
    }

    public void setPgTransactionId(String pgTransactionId) {
        this.pgTransactionId = pgTransactionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
