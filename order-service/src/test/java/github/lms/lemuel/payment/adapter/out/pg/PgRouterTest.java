package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 다중 PG 라우팅 의사결정 로직 검증.
 *
 * <p>실제 어댑터 (Toss/KCP/NICE/INICIS) 는 CircuitBreakerRegistry 를 의존하므로
 * 여기서는 가벼운 인 메모리 스텁 {@link FakeAdapter} 를 사용해 라우팅 의사결정만 격리 테스트.
 */
class PgRouterTest {

    private FakeAdapter toss;
    private FakeAdapter nice;
    private FakeAdapter kcp;
    private FakeAdapter inicis;

    @BeforeEach
    void setup() {
        toss = new FakeAdapter(PaymentGateway.TOSS, true,
                List.of("CARD", "TOSS_PAYMENTS", "BANK_TRANSFER"));
        nice = new FakeAdapter(PaymentGateway.NICE, true,
                List.of("CARD", "KAKAO_PAY", "NAVER_PAY"));
        kcp = new FakeAdapter(PaymentGateway.KCP, true,
                List.of("CARD", "BANK_TRANSFER", "VIRTUAL_ACCOUNT"));
        inicis = new FakeAdapter(PaymentGateway.INICIS, true,
                List.of("CARD", "BANK_TRANSFER"));
    }

    @Test
    @DisplayName("CARD 결제는 1순위 매핑 (TOSS) 로 라우팅된다")
    void primaryMapping_card() {
        PgRouter router = router(Map.of("CARD", PaymentGateway.TOSS));

        PaymentGatewayAdapter selected = router.selectFor(new BigDecimal("10000"), "CARD");

        assertThat(selected.provider()).isEqualTo(PaymentGateway.TOSS);
    }

    @Test
    @DisplayName("KAKAO_PAY 는 1순위 NICE 로 라우팅된다 (TOSS·KCP·INICIS 미지원)")
    void primaryMapping_kakaoPay() {
        PgRouter router = router(Map.of("KAKAO_PAY", PaymentGateway.NICE));

        PaymentGatewayAdapter selected = router.selectFor(new BigDecimal("5000"), "KAKAO_PAY");

        assertThat(selected.provider()).isEqualTo(PaymentGateway.NICE);
    }

    @Test
    @DisplayName("1순위 PG 가 OPEN 이면 fallback 체인의 다음 healthy PG 로 자동 전환")
    void fallback_whenPrimaryUnhealthy() {
        toss.unhealthy();
        PgRouter router = router(Map.of("CARD", PaymentGateway.TOSS));

        PaymentGatewayAdapter selected = router.selectFor(new BigDecimal("10000"), "CARD");

        // fallback-chain: TOSS, NICE, KCP, INICIS — TOSS 는 OPEN, NICE 가 다음
        assertThat(selected.provider()).isEqualTo(PaymentGateway.NICE);
    }

    @Test
    @DisplayName("100만원 이상은 high-amount 우선 PG 로 강제 라우팅 (1순위 매핑 무시)")
    void highAmountPreferred_overridesPrimary() {
        PgRouter router = router(Map.of("CARD", PaymentGateway.TOSS));

        PaymentGatewayAdapter selected = router.selectFor(new BigDecimal("1500000"), "CARD");

        assertThat(selected.provider()).isEqualTo(PaymentGateway.NICE);
    }

    @Test
    @DisplayName("모든 PG 가 OPEN 이면 IllegalStateException 으로 즉시 실패")
    void allUnhealthy_throws() {
        toss.unhealthy();
        nice.unhealthy();
        kcp.unhealthy();
        inicis.unhealthy();
        PgRouter router = router(Map.of("CARD", PaymentGateway.TOSS));

        assertThatThrownBy(() -> router.selectFor(new BigDecimal("10000"), "CARD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PG");
    }

    @Test
    @DisplayName("거래 ID prefix 로 동일 PG 의 어댑터를 정확히 찾는다 (capture/refund 라우팅)")
    void resolveByTransactionId_routesToOriginalPg() {
        PgRouter router = router(Map.of());

        assertThat(router.resolveByTransactionId("KCP:abc-123").provider()).isEqualTo(PaymentGateway.KCP);
        assertThat(router.resolveByTransactionId("NICE:xyz-456").provider()).isEqualTo(PaymentGateway.NICE);
        assertThat(router.resolveByTransactionId("TOSS:tok-789").provider()).isEqualTo(PaymentGateway.TOSS);
    }

    @Test
    @DisplayName("결제 수단을 지원하지 않는 PG 는 후보에서 자동 제외")
    void unsupportedMethod_isExcluded() {
        // KAKAO_PAY 는 TOSS·KCP·INICIS 가 미지원 → NICE 만 가능
        PgRouter router = router(Map.of()); // primary 매핑 없음

        PaymentGatewayAdapter selected = router.selectFor(new BigDecimal("5000"), "KAKAO_PAY");

        assertThat(selected.provider()).isEqualTo(PaymentGateway.NICE);
    }

    @Test
    @DisplayName("healthSnapshot 은 모든 등록된 PG 의 상태를 반환")
    void healthSnapshot() {
        toss.unhealthy();
        PgRouter router = router(Map.of());

        Map<PaymentGateway, Boolean> snap = router.healthSnapshot();

        assertThat(snap).containsEntry(PaymentGateway.TOSS, false);
        assertThat(snap).containsEntry(PaymentGateway.NICE, true);
        assertThat(snap).containsEntry(PaymentGateway.KCP, true);
        assertThat(snap).containsEntry(PaymentGateway.INICIS, true);
    }

    private PgRouter router(Map<String, PaymentGateway> primaryMapping) {
        PgRoutingProperties props = new PgRoutingProperties();
        props.setPrimaryByMethod(primaryMapping);
        props.setFallbackChain(List.of(
                PaymentGateway.TOSS, PaymentGateway.NICE,
                PaymentGateway.KCP, PaymentGateway.INICIS));
        props.setHighAmountThreshold(new BigDecimal("1000000"));
        props.setHighAmountPreferred(PaymentGateway.NICE);

        return new PgRouter(
                List.of(toss, nice, kcp, inicis),
                props,
                new SimpleMeterRegistry()
        );
    }

    /**
     * 테스트 전용 인 메모리 PaymentGatewayAdapter — CircuitBreaker 레지스트리 의존 제거.
     */
    private static class FakeAdapter implements PaymentGatewayAdapter {
        private final PaymentGateway provider;
        private boolean healthy;
        private final List<String> supportedMethods;

        FakeAdapter(PaymentGateway provider, boolean healthy, List<String> supportedMethods) {
            this.provider = provider;
            this.healthy = healthy;
            this.supportedMethods = supportedMethods;
        }

        void unhealthy() { this.healthy = false; }

        @Override public PaymentGateway provider() { return provider; }
        @Override public boolean supports(String method) {
            return method != null && supportedMethods.contains(method.toUpperCase());
        }
        @Override public boolean isHealthy() { return healthy; }
        @Override public String authorize(Long id, BigDecimal amount, String method) {
            return provider.prefix() + ":fake-" + id;
        }
        @Override public void capture(String txnId, BigDecimal amount) { }
        @Override public void refund(String txnId, BigDecimal amount, String idempotencyKey) { }
    }
}
