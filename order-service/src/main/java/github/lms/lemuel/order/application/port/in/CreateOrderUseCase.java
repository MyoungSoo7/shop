package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.math.BigDecimal;

public interface CreateOrderUseCase {

    Order createOrder(CreateOrderCommand command);

    /**
     * @param amount 요청이 주장하는 결제 금액. <b>주문 금액의 권위는 상품 마스터에 있다</b> —
     *               이 값은 서버가 계산한 금액과 대조하는 확인용이며, {@code null} 이면 대조를
     *               생략하고 상품 가격을 그대로 쓴다. 값이 서버 계산과 다르면 주문은 거절된다.
     */
    record CreateOrderCommand(
            Long userId,
            Long productId,
            BigDecimal amount
    ) {
        public CreateOrderCommand {
            if (userId == null) {
                throw new OrderInvariantViolationException("User ID cannot be null");
            }
            if (productId == null) {
                throw new OrderInvariantViolationException("Product ID cannot be null");
            }
            if (amount != null && amount.signum() <= 0) {
                throw new OrderInvariantViolationException("Amount must be greater than zero");
            }
        }
    }
}
