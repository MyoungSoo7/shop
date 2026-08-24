package github.lms.lemuel.payment.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 토스페이먼츠 장바구니 일괄 결제 확인 요청 DTO
 * 여러 주문을 하나의 Toss 결제로 처리
 */
public class TossCartConfirmRequest {

    @NotEmpty(message = "Order IDs are required")
    private List<@NotNull @Positive Long> orderIds;

    @NotBlank(message = "Payment key is required")
    private String paymentKey;

    @NotBlank(message = "Toss Order ID is required")
    private String tossOrderId;

    @NotNull(message = "Total amount is required")
    @Positive(message = "Total amount must be positive")
    private Long totalAmount;

    public List<Long> getOrderIds() { return orderIds; }
    public void setOrderIds(List<Long> orderIds) { this.orderIds = orderIds; }

    public String getPaymentKey() { return paymentKey; }
    public void setPaymentKey(String paymentKey) { this.paymentKey = paymentKey; }

    public String getTossOrderId() { return tossOrderId; }
    public void setTossOrderId(String tossOrderId) { this.tossOrderId = tossOrderId; }

    public Long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Long totalAmount) { this.totalAmount = totalAmount; }
}