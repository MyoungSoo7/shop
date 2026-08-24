package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InicisPgAdapterTest {

    @Mock CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock CircuitBreaker circuitBreaker;

    private InicisPgAdapter adapter() {
        return new InicisPgAdapter(circuitBreakerRegistry);
    }

    @Test
    @DisplayName("provider: INICIS")
    void provider() {
        assertThat(adapter().provider()).isEqualTo(PaymentGateway.INICIS);
    }

    @Test
    @DisplayName("supports: PAYCO 지원, KAKAO_PAY/null 은 false")
    void supports() {
        InicisPgAdapter adapter = adapter();
        assertThat(adapter.supports("PAYCO")).isTrue();
        assertThat(adapter.supports("payco")).isTrue();
        assertThat(adapter.supports("KAKAO_PAY")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("isHealthy: OPEN 이 아니면 true")
    void isHealthy_true() {
        when(circuitBreakerRegistry.circuitBreaker("inicisPg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        assertThat(adapter().isHealthy()).isTrue();
    }

    @Test
    @DisplayName("isHealthy: OPEN 이면 false")
    void isHealthy_open() {
        when(circuitBreakerRegistry.circuitBreaker("inicisPg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        assertThat(adapter().isHealthy()).isFalse();
    }

    @Test
    @DisplayName("authorize: INICIS prefix 가 붙은 거래ID 를 반환")
    void authorize_returnsPrefixedTransactionId() {
        String txnId = adapter().authorize(1L, new BigDecimal("10000"), "CARD");

        assertThat(txnId).startsWith("INICIS:");
    }

    @Test
    @DisplayName("capture/refund 는 예외 없이 완료된다")
    void captureAndRefund_noException() {
        InicisPgAdapter adapter = adapter();
        adapter.capture("INICIS:tx-1", new BigDecimal("10000"));
        adapter.refund("INICIS:tx-1", new BigDecimal("5000"), "idem-1");
    }
}
