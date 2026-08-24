package github.lms.lemuel.payment.application;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 토스페이먼츠 결제 확인 API 호출 전담 빈 (Resilience4j 보호 경계).
 *
 * <p><b>왜 별도 빈인가</b> — 스프링 AOP 는 프록시 기반이라 같은 빈 안에서 {@code this.call()} 로
 * 부른 메서드에는 어드바이스가 걸리지 않는다. 이 호출은 원래 {@code TossPaymentService} 안의
 * 메서드였고 {@code @CircuitBreaker}/{@code @Retry} 가 붙어 있었지만, 같은 클래스의
 * {@code confirmTossPayment()} 가 자기호출로 부르고 있어 <b>재시도도 서킷도 실제로는 한 번도
 * 동작하지 않았다</b>. 호출 대상을 별도 빈으로 분리(구조 변경)해 호출이 프록시를 통과하게 만든다.
 * 자기 자신 주입·{@code ObjectProvider} 지연 조회는 순환 참조를 남기므로 채택하지 않았다.
 *
 * <p>회귀 방지는 {@code scripts/harness/test/aop-proxy-gate.test.mjs} 가 리포 전수로 강제한다.
 *
 * <p>보호 정책 (ADR 0006):
 * <ul>
 *   <li>Retry: 네트워크 I/O·5xx 는 exponential backoff 로 재시도</li>
 *   <li>CircuitBreaker: 실패율 임계 초과 시 OPEN, {@code tossFallback} 으로 위임</li>
 *   <li>4xx({@link HttpClientErrorException})는 PG 비즈니스 오류라 재시도·서킷 판정에서 제외</li>
 * </ul>
 */
@Component
public class TossConfirmApiClient {

    private static final Logger log = LoggerFactory.getLogger(TossConfirmApiClient.class);
    private static final String PG_INSTANCE = "tossPg";

    @Value("${toss.secret-key}")
    private String secretKey;

    @Value("${toss.api-url}")
    private String tossApiUrl;

    private final RestTemplate restTemplate;

    public TossConfirmApiClient() {
        // Boot 4 에서 RestTemplateBuilder 가 제거되어 SimpleClientHttpRequestFactory 로 직접 구성.
        // connect/read timeout 으로 쓰레드 풀 고갈 방지 — 상위는 Resilience4j 가 담당.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Toss Payments 결제 확인 API 호출.
     *
     * <p>반드시 <b>다른 빈에서</b> 호출해야 한다. 같은 클래스 안에서 부르면 프록시를 타지 않아
     * 아래 두 애노테이션이 무력화된다.
     */
    @CircuitBreaker(name = PG_INSTANCE, fallbackMethod = "tossFallback")
    @Retry(name = PG_INSTANCE)
    public void confirm(String paymentKey, String tossOrderId, Long amount) {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);

        Map<String, Object> body = new HashMap<>();
        body.put("paymentKey", paymentKey);
        body.put("orderId", tossOrderId);
        body.put("amount", amount);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tossApiUrl, entity, Map.class);
            log.info("Toss API 응답: status={}", response.getStatusCode());

            if (!response.getStatusCode().is2xxSuccessful()) {
                String msg = extractTossError(response.getBody());
                // 외부 PG(Toss) 통합 실패 — 도메인 상태/입력 불변식이 아니라 외부 연동 오류이므로 generic 유지(사유 명시).
                throw new IllegalStateException("Toss 결제 확인 실패: " + msg);
            }

        } catch (HttpClientErrorException e) {
            // 4xx — Toss 비즈니스 에러. 재시도·서킷 대상 아님. 그대로 전파.
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.error("Toss API 4xx: status={}, body={}", e.getStatusCode(), responseBody);
            throw new IllegalStateException("Toss 결제 확인 실패 (" + e.getStatusCode() + "): " + responseBody, e);
        }
    }

    /**
     * CircuitBreaker Fallback — 시그니처는 원본 메서드 + Throwable.
     * 서킷 OPEN 또는 재시도 모두 소진 시 호출된다.
     * 운영에서는 여기서 Alertmanager/Slack webhook 알림 발송까지 연계.
     */
    @SuppressWarnings("unused") // Resilience4j 가 리플렉션으로 호출
    public void tossFallback(String paymentKey, String tossOrderId, Long amount, Throwable t) {
        // 4xx 비즈니스 오류는 그대로 상위로 전파
        if (t instanceof IllegalStateException ise) {
            throw ise;
        }
        log.error("Toss PG 서킷 오픈 또는 재시도 소진: paymentKey={}, orderId={}, amount={}, cause={}",
                paymentKey, tossOrderId, amount, t.toString());
        throw new IllegalStateException(
                "Toss PG 일시 장애로 결제 확인을 완료할 수 없습니다. 잠시 후 다시 시도해주세요.", t);
    }

    private String extractTossError(Map<?, ?> body) {
        if (body == null) return "알 수 없는 오류";
        Object code = body.get("code");
        Object message = body.get("message");
        return code + " - " + message;
    }
}
