package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Port for loading order information from Order bounded context
 */
public interface LoadOrderPort {
    OrderInfo loadOrder(Long orderId);

    class OrderInfo {
        private final Long id;
        private final Long userId;
        private final BigDecimal amount;
        private final String status;

        public OrderInfo(Long id, Long userId, BigDecimal amount, String status) {
            this.id = id;
            this.userId = userId;
            this.amount = amount;
            this.status = status;
        }

        /**
         * 소유자를 모르는 호출부용 하위 호환 생성자.
         *
         * <p>{@code userId} 가 없으면 소유권 대조가 <b>불가능</b>하다는 뜻이므로, 이 값을 받는
         * 검증부는 통과가 아니라 거부해야 한다(fail-closed). 운영 경로인
         * {@code OrderAdapter} 는 항상 4-인자 생성자로 소유자를 채운다.
         */
        public OrderInfo(Long id, BigDecimal amount, String status) {
            this(id, null, amount, status);
        }

        public Long getId() {
            return id;
        }

        /** 주문 소유자(users.id). 소유권 대조는 요청 파라미터가 아니라 이 값을 기준으로 한다. */
        public Long getUserId() {
            return userId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getStatus() {
            return status;
        }

        public boolean isCreated() {
            return "CREATED".equals(status);
        }
    }
}
