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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 토스페이먼츠 결제 취소(환불) API 호출 전담 빈 (Resilience4j 보호 경계).
 *
 * <p><b>왜 별도 빈인가</b> — {@link TossConfirmApiClient} 와 같은 이유다. 스프링 AOP 는 프록시
 * 기반이라 같은 빈 안에서 자기호출한 메서드에는 어드바이스가 걸리지 않는다. 환불 어댑터
 * ({@code TossLivePgAdapter}) 가 이 빈을 주입받아 호출해야 재시도·서킷이 실제로 동작한다.
 * 회귀 방지는 {@code scripts/harness/test/aop-proxy-gate.test.mjs} 가 리포 전수로 강제한다.
 *
 * <p><b>멱등</b> — Toss 는 {@code Idempotency-Key} 헤더를 지원한다. 같은 키로 재요청하면 중복
 * 취소하지 않고 최초 결과를 돌려준다. "PG 는 성공했는데 우리 DB 커밋이 실패" 후 자동 재시도가
 * 이중 환불이 되는 것을 막는 유일한 장치이므로, 키 없이 호출하는 것을 허용하지 않는다.
 *
 * <p>보호 정책은 ADR 0006 을 그대로 따른다 — 4xx 는 PG 비즈니스 오류라 재시도·서킷 판정에서
 * 제외되고({@code ignoreExceptions}), 5xx·네트워크 오류만 재시도 후 서킷 판정에 들어간다.
 */
@Component
public class TossCancelApiClient {

    private static final Logger log = LoggerFactory.getLogger(TossCancelApiClient.class);
    private static final String PG_INSTANCE = "tossPg";

    @Value("${toss.secret-key}")
    private String secretKey;

    @Value("${toss.cancel-url}")
    private String cancelUrlTemplate;

    private final RestTemplate restTemplate;

    public TossCancelApiClient() {
        // confirm 쪽과 동일한 타임아웃 정책 — 상위 보호는 Resilience4j 가 담당한다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Toss 결제 취소 API 호출.
     *
     * <p>반드시 <b>다른 빈에서</b> 호출해야 한다 — 자기호출은 프록시를 타지 않아 아래 두 애노테이션이
     * 무력화된다.
     *
     * @param paymentKey     Toss paymentKey. 저장된 {@code pgTransactionId} 의 {@code "TOSS:"} prefix 를
     *                       떼어낸 값이다.
     * @param cancelAmount   부분 취소 금액. {@code null} 이면 전액 취소.
     * @param idempotencyKey {@code Idempotency-Key} 헤더로 전달. 필수.
     * @param cancelReason   Toss 가 요구하는 취소 사유(필수 필드).
     */
    @CircuitBreaker(name = PG_INSTANCE, fallbackMethod = "cancelFallback")
    @Retry(name = PG_INSTANCE)
    public void cancel(String paymentKey, BigDecimal cancelAmount, String idempotencyKey, String cancelReason) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 멱등 키 없는 환불은 재시도 한 번에 이중 환불이 된다 — 호출 자체를 거부한다.
            throw new IllegalArgumentException("Toss 환불에는 멱등 키가 필요합니다: paymentKey=" + paymentKey);
        }

        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        headers.set("Idempotency-Key", idempotencyKey);

        Map<String, Object> body = new HashMap<>();
        body.put("cancelReason", cancelReason);
        if (cancelAmount != null) {
            // 원화는 소수 단위가 없다. 소수부가 남아 있으면 금액 계산이 어딘가에서 깨진 것이므로
            // longValueExact 가 ArithmeticException 으로 드러내게 둔다 — 반올림으로 덮지 않는다.
            body.put("cancelAmount", cancelAmount.longValueExact());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(cancelUrlTemplate, entity, Map.class, paymentKey);
            log.info("Toss 취소 응답: status={}, amount={}", response.getStatusCode(), cancelAmount);

            if (!response.getStatusCode().is2xxSuccessful()) {
                // 외부 PG 연동 실패 — 도메인 불변식이 아니므로 generic 유지(사유 명시).
                throw new IllegalStateException("Toss 결제 취소 실패: " + extractTossError(response.getBody()));
            }

        } catch (HttpClientErrorException e) {
            // 4xx — Toss 비즈니스 에러(이미 취소됨, 취소 가능 금액 초과 등). 재시도·서킷 대상 아님.
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.error("Toss 취소 4xx: status={}, body={}", e.getStatusCode(), responseBody);
            throw new IllegalStateException(
                    "Toss 결제 취소 실패 (" + e.getStatusCode() + "): " + responseBody, e);
        }
    }

    /**
     * CircuitBreaker Fallback — 시그니처는 원본 메서드 + Throwable.
     *
     * <p>환불은 승인과 달리 "실패했으니 다시 시도하세요"가 안전한 안내다. 여기서 성공으로 삼키면
     * 고객 돈이 돌아가지 않은 채 원장만 환불 처리된다.
     */
    @SuppressWarnings("unused") // Resilience4j 가 리플렉션으로 호출
    public void cancelFallback(String paymentKey, BigDecimal cancelAmount, String idempotencyKey,
                               String cancelReason, Throwable t) {
        if (t instanceof IllegalStateException ise) {
            throw ise;
        }
        if (t instanceof IllegalArgumentException iae) {
            throw iae;
        }
        log.error("Toss 취소 서킷 오픈 또는 재시도 소진: amount={}, idempotencyKey={}, cause={}",
                cancelAmount, idempotencyKey, t.toString());
        throw new IllegalStateException(
                "Toss PG 일시 장애로 환불을 완료할 수 없습니다. 잠시 후 다시 시도해주세요.", t);
    }

    private String extractTossError(Map<?, ?> body) {
        if (body == null) return "알 수 없는 오류";
        return body.get("code") + " - " + body.get("message");
    }
}
