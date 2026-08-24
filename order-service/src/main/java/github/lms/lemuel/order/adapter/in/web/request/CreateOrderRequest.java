package github.lms.lemuel.order.adapter.in.web.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotNull(message = "User ID is required")
    @Positive(message = "User ID must be positive")
    private Long userId;

    @NotNull(message = "Product ID is required")
    @Positive(message = "Product ID must be positive")
    private Long productId;

    /**
     * 클라이언트가 화면에서 본 결제 금액(선택). 주문 금액의 권위는 상품 마스터에 있고 이 값은
     * 대조용이다 — 생략하면 서버가 상품 가격으로 확정하고, 값이 다르면 400 으로 거절한다.
     */
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;
}
