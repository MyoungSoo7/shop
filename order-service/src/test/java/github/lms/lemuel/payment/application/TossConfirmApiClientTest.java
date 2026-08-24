package github.lms.lemuel.payment.application;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Toss 결제 확인 API 호출 빈 단위 테스트.
 *
 * <p>내부 RestTemplate 을 리플렉션으로 꺼내 {@link MockRestServiceServer} 를 바인딩해
 * 실제 HTTP 왕복 없이 응답 처리를 검증한다.
 */
class TossConfirmApiClientTest {

    private static final String TOSS_API_URL = "https://api.tosspayments.com/v1/payments/confirm";

    private TossConfirmApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        client = new TossConfirmApiClient();
        ReflectionTestUtils.setField(client, "secretKey", "test_sk_dummy");
        ReflectionTestUtils.setField(client, "tossApiUrl", TOSS_API_URL);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("confirm: 2xx 응답이면 예외 없이 통과")
    void confirm_success() {
        server.expect(requestTo(TOSS_API_URL))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"DONE\"}", MediaType.APPLICATION_JSON));

        client.confirm("pay-key", "toss-order", 1000L);

        server.verify();
    }

    @Test
    @DisplayName("confirm: 4xx 응답은 HttpClientErrorException → IllegalStateException 변환")
    void confirm_4xxThrowsIllegalState() {
        server.expect(requestTo(TOSS_API_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INVALID_REQUEST\",\"message\":\"bad request\"}"));

        assertThatThrownBy(() -> client.confirm("pay-key", "toss-order", 1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss 결제 확인 실패");
        server.verify();
    }

    @Test
    @DisplayName("confirm: 2xx 가 아닌데 예외를 던지지 않는 응답(3xx)은 extractTossError 로 메시지 구성")
    void confirm_non2xxNoException() {
        server.expect(requestTo(TOSS_API_URL))
                .andRespond(withRawStatus(302)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REDIRECT\",\"message\":\"unexpected\"}"));

        assertThatThrownBy(() -> client.confirm("pay-key", "toss-order", 1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIRECT")
                .hasMessageContaining("unexpected");
        server.verify();
    }

    @Test
    @DisplayName("confirm: 응답 본문이 없으면 '알 수 없는 오류' 로 메시지 구성")
    void confirm_non2xxWithoutBody() {
        server.expect(requestTo(TOSS_API_URL))
                .andRespond(withRawStatus(302));

        assertThatThrownBy(() -> client.confirm("pay-key", "toss-order", 1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("알 수 없는 오류");
        server.verify();
    }

    @Test
    @DisplayName("tossFallback: 원인이 IllegalStateException 이면 그대로 재전파")
    void tossFallback_rethrowsIllegalStateException() {
        IllegalStateException cause = new IllegalStateException("Toss 결제 확인 실패 (400 BAD_REQUEST): bad");

        assertThatThrownBy(() -> client.tossFallback("pay-key", "toss-order", 1000L, cause))
                .isSameAs(cause);
    }

    @Test
    @DisplayName("tossFallback: 서킷 OPEN/재시도 소진 등 그 외 원인은 일시 장애 메시지로 감싼다")
    void tossFallback_wrapsOtherThrowable() {
        RuntimeException cause = new RuntimeException("circuit open");

        assertThatThrownBy(() -> client.tossFallback("pay-key", "toss-order", 1000L, cause))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss PG 일시 장애")
                .hasCause(cause);
    }

    /**
     * 프록시 전제 검증 — 스프링 AOP 는 프록시 기반이라 어드바이스가 걸릴 메서드는
     * {@code public} 이고 {@code final} 이 아니어야 한다. (고급편 §14 프록시 기술의 한계)
     */
    @Test
    @DisplayName("confirm 은 @CircuitBreaker + @Retry 가 붙은 public non-final 메서드다")
    void confirmIsProxyable() throws NoSuchMethodException {
        Method confirm = TossConfirmApiClient.class.getMethod("confirm", String.class, String.class, Long.class);

        assertThat(confirm.isAnnotationPresent(CircuitBreaker.class)).isTrue();
        assertThat(confirm.isAnnotationPresent(Retry.class)).isTrue();
        assertThat(Modifier.isFinal(confirm.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(TossConfirmApiClient.class.getModifiers())).isFalse();
    }
}
