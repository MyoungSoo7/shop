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
class KcpPgAdapterTest {

    @Mock CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock CircuitBreaker circuitBreaker;

    private KcpPgAdapter adapter() {
        return new KcpPgAdapter(circuitBreakerRegistry);
    }

    @Test
    @DisplayName("provider: KCP")
    void provider() {
        assertThat(adapter().provider()).isEqualTo(PaymentGateway.KCP);
    }

    @Test
    @DisplayName("supports: VIRTUAL_ACCOUNT 지원, KAKAO_PAY/null 은 false")
    void supports() {
        KcpPgAdapter adapter = adapter();
        assertThat(adapter.supports("VIRTUAL_ACCOUNT")).isTrue();
        assertThat(adapter.supports("virtual_account")).isTrue();
        assertThat(adapter.supports("KAKAO_PAY")).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    @DisplayName("isHealthy: OPEN 이 아니면 true")
    void isHealthy_true() {
        when(circuitBreakerRegistry.circuitBreaker("kcpPg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        assertThat(adapter().isHealthy()).isTrue();
    }

    @Test
    @DisplayName("isHealthy: OPEN 이면 false")
    void isHealthy_open() {
        when(circuitBreakerRegistry.circuitBreaker("kcpPg")).thenReturn(circuitBreaker);
        when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        assertThat(adapter().isHealthy()).isFalse();
    }

    @Test
    @DisplayName("authorize: KCP prefix 가 붙은 거래ID 를 반환")
    void authorize_returnsPrefixedTransactionId() {
        String txnId = adapter().authorize(1L, new BigDecimal("10000"), "BANK_TRANSFER");

        assertThat(txnId).startsWith("KCP:");
    }

    @Test
    @DisplayName("capture/refund 는 예외 없이 완료된다")
    void captureAndRefund_noException() {
        KcpPgAdapter adapter = adapter();
        adapter.capture("KCP:tx-1", new BigDecimal("10000"));
        adapter.refund("KCP:tx-1", new BigDecimal("5000"), "idem-1");
    }
}
