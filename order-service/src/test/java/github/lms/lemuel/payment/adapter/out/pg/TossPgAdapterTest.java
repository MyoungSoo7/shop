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
class TossPgAdapterTest {

    @Mock CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock CircuitBreaker circuitBreaker;

    private TossPgAdapter adapter() {
        return new TossPgAdapter(circuitBreakerRegistry);
    }

    @Test
    @DisplayName("provider: TOSS")
    void provider() {
        assertThat(adapter().provider()).isEqualTo(PaymentGateway.TOSS);
    }

    @Test
    @DisplayName("supports: 지원 수단은 true, 미지원/null 은 false")
    void supports() {
        TossPgAdapter adapter = adapter();
        assertThat(adapter.supports("CARD")).isTrue();
        assertThat(adapter.supports("card")).isTrue();
        assertThat(adapter.supports("UNKNOWN_METHOD")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("isHealthy: CircuitBreaker 가 OPEN 이 아니면 true")
    void isHealthy_true() {
        when(circuitBreakerRegistry.circuitBreaker("tossPg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

        assertThat(adapter().isHealthy()).isTrue();
    }

    @Test
    @DisplayName("isHealthy: CircuitBreaker 가 OPEN 이면 false")
    void isHealthy_false() {
        when(circuitBreakerRegistry.circuitBreaker("tossPg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);

        assertThat(adapter().isHealthy()).isFalse();
    }

    @Test
    @DisplayName("authorize: TOSS prefix 가 붙은 거래ID 를 반환")
    void authorize_returnsPrefixedTransactionId() {
        String txnId = adapter().authorize(1L, new BigDecimal("10000"), "CARD");

        assertThat(txnId).startsWith("TOSS:");
    }

    @Test
    @DisplayName("capture/refund 는 예외 없이 완료된다")
    void captureAndRefund_noException() {
        TossPgAdapter adapter = adapter();
        adapter.capture("TOSS:tx-1", new BigDecimal("10000"));
        adapter.refund("TOSS:tx-1", new BigDecimal("5000"), "idem-1");
    }
}
