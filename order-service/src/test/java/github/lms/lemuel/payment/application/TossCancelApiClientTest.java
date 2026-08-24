package github.lms.lemuel.payment.application;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Toss 결제 취소(환불) API 호출 빈 단위 테스트.
 *
 * <p>{@link TossConfirmApiClientTest} 와 같은 방식 — 내부 RestTemplate 을 리플렉션으로 꺼내
 * {@link MockRestServiceServer} 를 바인딩해 실제 HTTP 왕복 없이 검증한다.
 */
class TossCancelApiClientTest {

    private static final String CANCEL_URL_TEMPLATE =
            "https://api.tosspayments.com/v1/payments/{paymentKey}/cancel";
    private static final String EXPANDED_URL =
            "https://api.tosspayments.com/v1/payments/pay-key/cancel";

    private TossCancelApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        client = new TossCancelApiClient();
        ReflectionTestUtils.setField(client, "secretKey", "test_sk_dummy");
        ReflectionTestUtils.setField(client, "cancelUrlTemplate", CANCEL_URL_TEMPLATE);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("cancel: paymentKey 가 URL 에 치환되고 멱등 키가 헤더로 나간다")
    void cancel_sendsIdempotencyKeyHeader() {
        server.expect(requestTo(EXPANDED_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-42"))
                .andExpect(header("Authorization", "Basic dGVzdF9za19kdW1teTo="))
                .andExpect(jsonPath("$.cancelAmount").value(1000))
                .andExpect(jsonPath("$.cancelReason").value("고객 환불 요청"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        client.cancel("pay-key", new BigDecimal("1000"), "refund-42", "고객 환불 요청");

        server.verify();
    }

    @Test
    @DisplayName("cancel: 금액이 null 이면 cancelAmount 를 보내지 않는다(전액 취소)")
    void cancel_nullAmountOmitsCancelAmount() {
        server.expect(requestTo(EXPANDED_URL))
                .andExpect(jsonPath("$.cancelAmount").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        client.cancel("pay-key", null, "refund-43", "전액 환불");

        server.verify();
    }

    @Test
    @DisplayName("cancel: 소수부가 남은 금액은 반올림하지 않고 ArithmeticException 으로 드러낸다")
    void cancel_fractionalAmountThrows() {
        assertThatThrownBy(() ->
                client.cancel("pay-key", new BigDecimal("1000.50"), "refund-44", "부분 환불"))
                .isInstanceOf(ArithmeticException.class);

        server.verify(); // 요청이 나가지 않았음
    }

    @Test
    @DisplayName("cancel: 멱등 키가 없으면 호출 자체를 거부한다 (재시도 이중 환불 방지)")
    void cancel_blankIdempotencyKeyRejected() {
        assertThatThrownBy(() -> client.cancel("pay-key", new BigDecimal("1000"), "  ", "부분 환불"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("멱등 키");

        assertThatThrownBy(() -> client.cancel("pay-key", new BigDecimal("1000"), null, "부분 환불"))
                .isInstanceOf(IllegalArgumentException.class);

        server.verify(); // 요청이 나가지 않았음
    }

    @Test
    @DisplayName("cancel: 4xx 는 IllegalStateException 으로 변환 (재시도·서킷 대상 아님)")
    void cancel_4xxThrowsIllegalState() {
        server.expect(requestTo(EXPANDED_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_CANCELED_PAYMENT\",\"message\":\"이미 취소됨\"}"));

        assertThatThrownBy(() ->
                client.cancel("pay-key", new BigDecimal("1000"), "refund-45", "부분 환불"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss 결제 취소 실패")
                .hasMessageContaining("ALREADY_CANCELED_PAYMENT");
        server.verify();
    }

    @Test
    @DisplayName("cancel: 예외를 안 던지는 비 2xx 응답도 실패로 취급한다")
    void cancel_non2xxTreatedAsFailure() {
        server.expect(requestTo(EXPANDED_URL))
                .andRespond(withRawStatus(302)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REDIRECT\",\"message\":\"unexpected\"}"));

        assertThatThrownBy(() ->
                client.cancel("pay-key", new BigDecimal("1000"), "refund-46", "부분 환불"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIRECT");
        server.verify();
    }

    @Test
    @DisplayName("cancelFallback: 4xx 유래 IllegalStateException 은 그대로 재전파")
    void cancelFallback_rethrowsIllegalState() {
        IllegalStateException cause = new IllegalStateException("Toss 결제 취소 실패 (400): bad");

        assertThatThrownBy(() ->
                client.cancelFallback("pay-key", BigDecimal.TEN, "refund-47", "사유", cause))
                .isSameAs(cause);
    }

    @Test
    @DisplayName("cancelFallback: 입력 검증 실패도 그대로 재전파 (일시 장애로 둔갑시키지 않는다)")
    void cancelFallback_rethrowsIllegalArgument() {
        IllegalArgumentException cause = new IllegalArgumentException("Toss 환불에는 멱등 키가 필요합니다");

        assertThatThrownBy(() ->
                client.cancelFallback("pay-key", BigDecimal.TEN, null, "사유", cause))
                .isSameAs(cause);
    }

    @Test
    @DisplayName("cancelFallback: 그 외 원인은 일시 장애로 감싸되 성공으로 삼키지 않는다")
    void cancelFallback_wrapsOtherThrowable() {
        RuntimeException cause = new RuntimeException("circuit open");

        assertThatThrownBy(() ->
                client.cancelFallback("pay-key", BigDecimal.TEN, "refund-48", "사유", cause))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss PG 일시 장애")
                .hasCause(cause);
    }

    /**
     * 프록시 전제 검증 — 어드바이스가 걸릴 메서드는 public 이고 final 이 아니어야 한다.
     */
    @Test
    @DisplayName("cancel 은 @CircuitBreaker + @Retry 가 붙은 public non-final 메서드다")
    void cancelIsProxyable() throws NoSuchMethodException {
        Method cancel = TossCancelApiClient.class.getMethod(
                "cancel", String.class, BigDecimal.class, String.class, String.class);

        assertThat(cancel.isAnnotationPresent(CircuitBreaker.class)).isTrue();
        assertThat(cancel.isAnnotationPresent(Retry.class)).isTrue();
        assertThat(Modifier.isFinal(cancel.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(TossCancelApiClient.class.getModifiers())).isFalse();
    }
}
