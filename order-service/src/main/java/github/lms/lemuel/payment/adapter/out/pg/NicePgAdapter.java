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
 * NICE Payments 어댑터.
 *
 * <p>국내 대형 PG. 간편결제 다수 지원.
 */
@Component
@Profile("!prod")
public class NicePgAdapter implements PaymentGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(NicePgAdapter.class);
    private static final String CB_INSTANCE = "nicePg";

    private static final Set<String> SUPPORTED = Set.of(
            "CARD", "BANK_TRANSFER", "KAKAO_PAY", "NAVER_PAY", "PAYCO", "SAMSUNG_PAY"
    );

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public NicePgAdapter(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public PaymentGateway provider() {
        return PaymentGateway.NICE;
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
        log.debug("[NICE] authorize paymentId={}, amount={}, method={}", paymentId, amount, paymentMethod);
        return PaymentGateway.NICE.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + UUID.randomUUID();
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        log.debug("[NICE] capture txnId={}, amount={}", pgTransactionId, amount);
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) {
        // 실 운영: NICE 취소 API 의 멱등 파라미터로 idempotencyKey 를 전달해 재시도 이중환불을 막는다.
        log.info("[NICE] refund txnId={}, amount={}, idempotencyKey={}", pgTransactionId, amount, idempotencyKey);
    }
}
