package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.application.TossCancelApiClient;
import github.lms.lemuel.payment.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 운영 Toss 어댑터 단위 테스트.
 *
 * <p>핵심 계약 3 가지를 못박는다 —
 * (1) authorize 는 가짜 승인 대신 실패한다, (2) capture 는 no-op 이다,
 * (3) refund 는 {@code "TOSS:"} prefix 를 떼고 실 취소 클라이언트에 위임한다.
 */
class TossLivePgAdapterTest {

    private TossCancelApiClient cancelApiClient;
    private CircuitBreakerRegistry registry;
    private TossLivePgAdapter adapter;

    @BeforeEach
    void setup() {
        cancelApiClient = mock(TossCancelApiClient.class);
        registry = CircuitBreakerRegistry.ofDefaults();
        adapter = new TossLivePgAdapter(cancelApiClient, registry);
    }

    @Test
    @DisplayName("provider 는 TOSS")
    void providerIsToss() {
        assertThat(adapter.provider()).isEqualTo(PaymentGateway.TOSS);
    }

    @Test
    @DisplayName("supports: mock 어댑터와 같은 결제 수단 목록 (프로파일에 따라 라우팅이 달라지지 않도록)")
    void supportsSameMethodsAsMock() {
        assertThat(adapter.supports("CARD")).isTrue();
        assertThat(adapter.supports("toss_payments")).isTrue();   // 대소문자 무관
        assertThat(adapter.supports("KAKAO_PAY")).isTrue();
        assertThat(adapter.supports("POINT")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("isHealthy: 서킷이 OPEN 이면 false, 그 외에는 true")
    void isHealthyFollowsCircuitBreakerState() {
        assertThat(adapter.isHealthy()).isTrue();

        CircuitBreaker cb = registry.circuitBreaker("tossPg");
        cb.transitionToOpenState();
        assertThat(adapter.isHealthy()).isFalse();

        cb.transitionToHalfOpenState();
        assertThat(adapter.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("authorize: 서버 개시 승인은 조용히 성공시키지 않고 명시적으로 실패한다")
    void authorizeIsRejected() {
        assertThatThrownBy(() -> adapter.authorize(42L, new BigDecimal("1000"), "CARD"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("결제창")
                .hasMessageContaining("paymentId=42");

        verifyNoInteractions(cancelApiClient);
    }

    @Test
    @DisplayName("capture: confirm 이 이미 매입까지 끝냈으므로 외부 호출 없이 통과")
    void captureIsNoOp() {
        adapter.capture("TOSS:pay-key", new BigDecimal("1000"));

        verifyNoInteractions(cancelApiClient);
    }

    @Test
    @DisplayName("refund: TOSS: prefix 를 떼고 raw paymentKey 로 취소를 위임한다")
    void refundStripsPrefixAndDelegates() {
        adapter.refund("TOSS:pay-key-123", new BigDecimal("500"), "refund-7");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cancelApiClient).cancel(keyCaptor.capture(), eq(new BigDecimal("500")),
                eq("refund-7"), any());
        assertThat(keyCaptor.getValue()).isEqualTo("pay-key-123");
    }

    @Test
    @DisplayName("refund: prefix 가 없는 과거 데이터는 그대로 전달한다")
    void refundPassesThroughLegacyIdWithoutPrefix() {
        adapter.refund("legacy-key", new BigDecimal("500"), "refund-8");

        verify(cancelApiClient, times(1))
                .cancel(eq("legacy-key"), eq(new BigDecimal("500")), eq("refund-8"), any());
    }

    /**
     * 프로파일 게이트가 상보적이어야 한다 — 겹치면 같은 provider 어댑터 2 개가 등록돼
     * {@code PgRouter} 의 {@code Map} 에서 하나가 조용히 덮이고, 둘 다 빠지면 fail-closed 가드가
     * 기동을 막는다(운영이 9 시간 멈췄던 그 상태).
     */
    @Test
    @DisplayName("프로파일: 실 어댑터는 prod, mock 어댑터는 !prod — 어느 프로파일에서도 정확히 하나")
    void profilesAreComplementary() {
        assertThat(TossLivePgAdapter.class.getAnnotation(org.springframework.context.annotation.Profile.class)
                .value()).containsExactly("prod");
        assertThat(TossPgAdapter.class.getAnnotation(org.springframework.context.annotation.Profile.class)
                .value()).containsExactly("!prod");
    }

    @Test
    @DisplayName("refund: 거래 ID 가 비어 있으면 PG 를 호출하지 않고 거부한다")
    void refundRejectsBlankTransactionId() {
        assertThatThrownBy(() -> adapter.refund(null, new BigDecimal("500"), "refund-9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.refund("  ", new BigDecimal("500"), "refund-9"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cancelApiClient);
    }
}
