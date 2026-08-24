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
class NicePgAdapterTest {

    @Mock CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock CircuitBreaker circuitBreaker;

    private NicePgAdapter adapter() {
        return new NicePgAdapter(circuitBreakerRegistry);
    }

    @Test
    @DisplayName("provider: NICE")
    void provider() {
        assertThat(adapter().provider()).isEqualTo(PaymentGateway.NICE);
    }

    @Test
    @DisplayName("supports: KAKAO_PAY 지원, 미지원/null 은 false")
    void supports() {
        NicePgAdapter adapter = adapter();
        assertThat(adapter.supports("KAKAO_PAY")).isTrue();
        assertThat(adapter.supports("kakao_pay")).isTrue();
        assertThat(adapter.supports("VIRTUAL_ACCOUNT")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("isHealthy: OPEN 이 아니면 true, OPEN 이면 false")
    void isHealthy() {
        when(circuitBreakerRegistry.circuitBreaker("nicePg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
        assertThat(adapter().isHealthy()).isTrue();
    }

    @Test
    @DisplayName("isHealthy: OPEN 이면 false")
    void isHealthy_open() {
        when(circuitBreakerRegistry.circuitBreaker("nicePg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        assertThat(adapter().isHealthy()).isFalse();
    }

    @Test
    @DisplayName("authorize: NICE prefix 가 붙은 거래ID 를 반환")
    void authorize_returnsPrefixedTransactionId() {
        String txnId = adapter().authorize(1L, new BigDecimal("10000"), "KAKAO_PAY");

        assertThat(txnId).startsWith("NICE:");
    }

    @Test
    @DisplayName("capture/refund 는 예외 없이 완료된다")
    void captureAndRefund_noException() {
        NicePgAdapter adapter = adapter();
        adapter.capture("NICE:tx-1", new BigDecimal("10000"));
        adapter.refund("NICE:tx-1", new BigDecimal("5000"), "idem-1");
    }
}
