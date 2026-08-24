package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * 이니시스(KG INICIS) 어댑터.
 *
 * <p>대형 PG. 카드·계좌이체 위주, 일부 간편결제 지원.
 */
@Component
@Profile("!prod")
public class InicisPgAdapter implements PaymentGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(InicisPgAdapter.class);
    private static final String CB_INSTANCE = "inicisPg";

    private static final Set<String> SUPPORTED = Set.of(
            "CARD", "BANK_TRANSFER", "VIRTUAL_ACCOUNT", "PAYCO"
    );

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public InicisPgAdapter(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public PaymentGateway provider() {
        return PaymentGateway.INICIS;
    }

    @Override
    public boolean supports(String paymentMethod) {
        return paymentMethod != null && SUPPORTED.contains(paymentMethod.toUpperCase());
    }

    @Override
    public boolean isHealthy() {
        CircuitBreaker.State state = circuitBreakerRegistry.circuitBreaker(CB_INSTANCE).getState();
        return state != CircuitBreaker.State.OPEN;
    }

    @Override
    public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
        log.debug("[INICIS] authorize paymentId={}, amount={}, method={}", paymentId, amount, paymentMethod);
        return PaymentGateway.INICIS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + UUID.randomUUID();
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        log.debug("[INICIS] capture txnId={}, amount={}", pgTransactionId, amount);
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) {
        // 실 운영: 이니시스 취소 API 의 멱등 파라미터로 idempotencyKey 를 전달해 재시도 이중환불을 막는다.
        log.info("[INICIS] refund txnId={}, amount={}, idempotencyKey={}", pgTransactionId, amount, idempotencyKey);
    }
}
