package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Port for external Payment Gateway integration
 */
public interface PgClientPort {
    /**
     * Authorize payment with PG
     * @return PG transaction ID
     */
    String authorize(Long paymentId, BigDecimal amount, String paymentMethod);

    /**
     * Capture authorized payment
     */
    void capture(String pgTransactionId, BigDecimal amount);

    /**
     * Refund captured payment.
     *
     * @param idempotencyKey PG 에 전달할 멱등 키. 같은 키로 재요청하면 PG 가 중복 환불을 무시하고
     *                       원래 결과를 돌려준다("PG 는 됐는데 우리 DB 는 실패" 후 자동 재시도 시 이중 환불 방지).
     *                       {@code null} 이면 멱등 보장 없이 실행된다 — 호출자는 가능하면 항상 키를 넘긴다.
     */
    void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey);
}
