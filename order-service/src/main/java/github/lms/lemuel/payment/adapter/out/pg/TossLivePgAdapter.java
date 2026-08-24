package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.application.TossCancelApiClient;
import github.lms.lemuel.payment.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 운영 프로파일용 <b>실</b> Toss Payments 어댑터.
 *
 * <p>{@link TossPgAdapter}(mock) 는 {@code @Profile("!prod")} 라 운영에서는 등록되지 않는다.
 * 그 결과 운영에서는 {@link PgRouter} 에 어댑터가 하나도 주입되지 않아 fail-closed 가드가
 * 기동을 거부해 왔다. 이 어댑터가 그 자리를 실제 연동으로 채운다.
 *
 * <h2>Toss 결제 모델과 이 클래스의 경계</h2>
 * Toss 는 <b>서버가 먼저 승인을 개시하는 API 가 없다</b>. 결제창(SDK/위젯)이 사용자 인증을
 * 마치고 {@code paymentKey} 를 발급하면, 서버는 그 키로 {@code /v1/payments/confirm} 을 호출해
 * 승인 + 매입을 동시에 마친다. 그래서 각 연산의 처리는 이렇게 갈린다:
 *
 * <ul>
 *   <li><b>authorize</b> — 구현 불가. 예외를 던진다(아래 참고).</li>
 *   <li><b>capture</b> — confirm 시점에 이미 매입까지 끝났으므로 no-op.</li>
 *   <li><b>refund</b> — {@code /v1/payments/{paymentKey}/cancel} 실호출.
 *       {@link TossCancelApiClient} 에 위임한다.</li>
 * </ul>
 *
 * <h2>authorize 가 예외를 던지는 것이 의도된 동작인 이유</h2>
 * mock 어댑터의 {@code authorize} 는 랜덤 UUID 를 돌려주며 <b>항상 성공</b>했다. 운영에서 그
 * 경로를 타면 돈이 한 푼도 움직이지 않은 결제가 승인·매입 상태로 원장에 남는다. 조용한 가짜
 * 승인보다 시끄러운 실패가 낫다 — 정상 결제는 결제창 → {@code TossPaymentService.confirmTossPayment}
 * 경로로만 들어와야 하며, 그 경로는 이 어댑터의 authorize 를 거치지 않는다.
 *
 * <p>{@link #supports(String)} 를 무조건 {@code false} 로 두지 않은 것도 같은 이유다. false 면
 * 라우터가 후보를 못 찾아 "결제 가능한 PG 가 없습니다" 라는 뭉뚱그린 메시지를 내지만, true 로
 * 두면 아래의 정확한 사유가 그대로 호출자에게 전달된다. 환불 라우팅
 * ({@link PgRouter#resolveByTransactionId}) 은 {@code supports} 를 보지 않으므로 영향이 없다.
 */
@Component
@Profile("prod")
public class TossLivePgAdapter implements PaymentGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(TossLivePgAdapter.class);
    private static final String CB_INSTANCE = "tossPg";

    /** mock 어댑터와 동일한 목록 — 라우팅 판정이 프로파일에 따라 달라지지 않게 한다. */
    private static final Set<String> SUPPORTED = Set.of(
            "CARD", "TOSS_PAYMENTS", "BANK_TRANSFER", "VIRTUAL_ACCOUNT", "KAKAO_PAY", "NAVER_PAY"
    );

    private static final String DEFAULT_CANCEL_REASON = "고객 환불 요청";

    private final TossCancelApiClient tossCancelApiClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public TossLivePgAdapter(TossCancelApiClient tossCancelApiClient,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.tossCancelApiClient = tossCancelApiClient;
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

    /**
     * Toss 에는 서버 개시 승인 API 가 없다 — 가짜 승인 대신 명시적으로 실패한다.
     */
    @Override
    public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
        log.error("[TOSS-LIVE] 서버 개시 authorize 시도 차단: paymentId={}, amount={}, method={}",
                paymentId, amount, paymentMethod);
        throw new UnsupportedOperationException(
                "Toss 는 서버에서 결제를 개시할 수 없습니다. 결제창(위젯)이 발급한 paymentKey 로 "
                        + "confirm 하는 경로(TossPaymentService.confirmTossPayment)를 사용해야 합니다. "
                        + "paymentId=" + paymentId);
    }

    /**
     * Toss 는 confirm 이 승인과 매입을 함께 처리하므로 별도 매입 호출이 없다.
     */
    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        log.debug("[TOSS-LIVE] capture 생략 (confirm 시점에 매입 완료): txnId={}, amount={}",
                pgTransactionId, amount);
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) {
        String paymentKey = stripPrefix(pgTransactionId);
        log.info("[TOSS-LIVE] refund 요청: amount={}, idempotencyKey={}", amount, idempotencyKey);
        // 별도 빈 호출 — 자기호출이면 @CircuitBreaker/@Retry 가 프록시를 못 타 조용히 죽는다.
        tossCancelApiClient.cancel(paymentKey, amount, idempotencyKey, DEFAULT_CANCEL_REASON);
    }

    /**
     * 저장된 {@code "TOSS:<paymentKey>"} 에서 Toss 가 아는 raw paymentKey 를 복원한다.
     * prefix 가 없는 과거 데이터는 그대로 통과시킨다.
     */
    private String stripPrefix(String pgTransactionId) {
        if (pgTransactionId == null || pgTransactionId.isBlank()) {
            throw new IllegalArgumentException("환불할 PG 거래 ID 가 없습니다");
        }
        String expected = PaymentGateway.TOSS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER;
        if (pgTransactionId.regionMatches(true, 0, expected, 0, expected.length())) {
            return pgTransactionId.substring(expected.length());
        }
        return pgTransactionId;
    }
}
