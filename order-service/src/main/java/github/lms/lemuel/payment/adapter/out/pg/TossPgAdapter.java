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
 * Toss Payments 어댑터.
 *
 * <p>실제 외부 호출은 {@link github.lms.lemuel.payment.application.TossPaymentService} 가
 * 별도로 담당하고 (Toss API 직접 호출 + Resilience4j), 이 어댑터는 라우팅 가능 여부 + Mock
 * 호출을 담당한다. 운영에서는 이 어댑터 안에서 RestTemplate / WebClient 를 호출하도록 확장.
 */
@Component
@Profile("!prod")
public class TossPgAdapter implements PaymentGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(TossPgAdapter.class);
    private static final String CB_INSTANCE = "tossPg";

    /**
     * Toss 가 지원하는 결제 수단. 운영에서는 PG 사 정책에 맞춰 외부 설정으로 분리.
     */
    private static final Set<String> SUPPORTED = Set.of(
            "CARD", "TOSS_PAYMENTS", "BANK_TRANSFER", "VIRTUAL_ACCOUNT", "KAKAO_PAY", "NAVER_PAY"
    );

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public TossPgAdapter(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public PaymentGateway provider() {
        return PaymentGateway.TOSS;
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
        log.debug("[TOSS] authorize paymentId={}, amount={}, method={}", paymentId, amount, paymentMethod);
        // 실 운영: TossPaymentService 의 confirmTossPayment 호출 또는 별도 authorize API
        return PaymentGateway.TOSS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + UUID.randomUUID();
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        log.debug("[TOSS] capture txnId={}, amount={}", pgTransactionId, amount);
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) {
        // 실 운영: Toss 환불(cancel) API 호출 시 idempotencyKey 를 HTTP `Idempotency-Key` 헤더로 전송한다.
        // 같은 키 재요청은 Toss 가 중복 취소하지 않고 최초 결과를 반환 → 자동 재시도의 이중 환불 방지.
        log.info("[TOSS] refund txnId={}, amount={}, idempotencyKey={}", pgTransactionId, amount, idempotencyKey);
    }
}
