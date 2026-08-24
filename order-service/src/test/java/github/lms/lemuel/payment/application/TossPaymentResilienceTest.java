package github.lms.lemuel.payment.application;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resilience4j 가 Toss PG 호출에 요구되는 동작을 실제로 보장하는지 검증.
 *
 * 여기서는 @CircuitBreaker/@Retry 어노테이션이 아니라 프로그래매틱 API 를 써서
 * 동일한 설정값이 원하는 행동을 내는지 빠르게 확인한다 (Spring 컨텍스트 기동 없이).
 */
class TossPaymentResilienceTest {

    @Test @DisplayName("Retry: ResourceAccessException 발생 시 재시도, 성공 이전까지 호출 횟수 증가")
    void retriesOnNetworkError() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(ResourceAccessException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        Retry retry = RetryRegistry.of(config).retry("tossPg");

        AtomicInteger calls = new AtomicInteger();
        Runnable failing = Retry.decorateRunnable(retry, () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("conn timeout");
        });

        assertThatThrownBy(failing::run).isInstanceOf(ResourceAccessException.class);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test @DisplayName("Retry: HttpClientErrorException (4xx) 은 ignore → 재시도 안 함")
    void doesNotRetryOn4xx() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(ResourceAccessException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        Retry retry = RetryRegistry.of(config).retry("tossPg");

        AtomicInteger calls = new AtomicInteger();
        Runnable clientErr = Retry.decorateRunnable(retry, () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "bad request", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        });

        assertThatThrownBy(clientErr::run).isInstanceOf(HttpClientErrorException.class);
        assertThat(calls.get()).isEqualTo(1); // 4xx 는 재시도 안 함
    }

    @Test @DisplayName("CircuitBreaker: 실패율 임계 초과 시 OPEN, 이후 호출은 CallNotPermitted")
    void opensAfterFailures() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("tossPg");

        Runnable failing = CircuitBreaker.decorateRunnable(cb, () -> {
            throw new ResourceAccessException("down");
        });

        // 4회 실패 → 실패율 100% > 50% → OPEN
        for (int i = 0; i < 4; i++) {
            try { failing.run(); } catch (Exception ignored) { }
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 상태에서의 다음 호출은 CallNotPermittedException
        assertThatThrownBy(failing::run)
                .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
    }
}
