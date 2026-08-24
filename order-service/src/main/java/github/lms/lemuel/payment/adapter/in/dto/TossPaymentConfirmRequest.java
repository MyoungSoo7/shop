package github.lms.lemuel.payment.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 토스페이먼츠 결제 확인 요청 DTO
 */
public class TossPaymentConfirmRequest {

    @NotNull(message = "DB Order ID is required")
    @Positive(message = "DB Order ID must be positive")
    private Long dbOrderId;

    @NotBlank(message = "Payment key is required")
    private String paymentKey;

    @NotBlank(message = "Toss Order ID is required")
    private String tossOrderId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private Long amount;

    public Long getDbOrderId() {
        return dbOrderId;
    }

    public void setDbOrderId(Long dbOrderId) {
        this.dbOrderId = dbOrderId;
    }

    public String getPaymentKey() {
        return paymentKey;
    }

    public void setPaymentKey(String paymentKey) {
        this.paymentKey = paymentKey;
    }

    public String getTossOrderId() {
        return tossOrderId;
    }

    public void setTossOrderId(String tossOrderId) {
        this.tossOrderId = tossOrderId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }
}