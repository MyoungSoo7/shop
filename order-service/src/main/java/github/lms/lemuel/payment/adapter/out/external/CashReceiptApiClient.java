package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.domain.CashReceipt;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 현금영수증 대행(PG) 전문 호출 전담 빈 — Resilience4j 보호 경계.
 *
 * <p><b>왜 어댑터와 분리된 별도 빈인가</b> — 스프링 AOP 는 프록시 기반이라 같은 빈 안에서
 * 자기호출한 메서드에는 어드바이스가 걸리지 않는다. {@link LiveCashReceiptGatewayAdapter} 가 이
 * 빈을 주입받아 호출해야 재시도·서킷이 실제로 동작한다. 회귀 방지는
 * {@code scripts/harness/test/aop-proxy-gate.test.mjs} 가 리포 전수로 강제한다
 * ({@code TossCancelApiClient} 와 같은 구조).
 *
 * <h2>전문 규격</h2>
 * 특정 대행사에 고정하지 않은 <b>범용 서명 전문</b>이다 — 엔드포인트·가맹점 ID·서명키는 설정에서
 * 오고, 서명은 {@code HMAC-SHA256(merchantId|requestId|totalAmount|식별값)} 의 소문자 hex 다.
 * 레거시 커머스(ssgb2e {@code OrderCashReceiptServiceImpl})가 PG 모듈에 HMAC 서명 전문을 직접
 * POST 하던 것과 같은 형태다. 대행사가 요구하는 필드명이 다르면 <b>이 클래스의 매핑만</b>
 * 바꾸면 되고, 도메인·서비스·화면은 건드리지 않는다.
 *
 * <h2>멱등</h2>
 * {@code Idempotency-Key} 는 결제 id 에서 결정적으로 만든다({@code CR-<paymentId>} /
 * {@code CRX-<paymentId>}). 난수로 만들면 재시도마다 키가 달라져 <b>같은 거래에 영수증이 두 장</b>
 * 발급된다 — 발급 취소는 사후 정정 신고 대상이라 되돌리는 비용이 크다.
 *
 * <h2>실패 분류</h2>
 * ADR 0006 의 PG 정책을 그대로 따른다 — 4xx 는 대행사 업무 오류라 재시도·서킷 판정에서 제외되고
 * ({@code ignoreExceptions}), 5xx·네트워크 오류만 재시도 후 서킷 판정에 들어간다.
 */
public class CashReceiptApiClient {

    private static final Logger log = LoggerFactory.getLogger(CashReceiptApiClient.class);

    /** Resilience4j 인스턴스 이름 — PG 결제와 격벽을 나눈다(세금 서류 장애가 결제를 끊지 않도록). */
    private static final String CB_INSTANCE = "cashReceipt";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SUCCESS_CODE = "0000";
    private static final String ISSUE_KEY_PREFIX = "CR-";
    private static final String CANCEL_KEY_PREFIX = "CRX-";

    private final String issueUrl;
    private final String cancelUrl;
    private final String merchantId;
    private final byte[] secretKey;
    private final RestTemplate restTemplate;

    /**
     * 운영 조립용.
     *
     * <p>자격증명 검증을 <b>생성 시점</b>에 두는 것이 핵심이다. 첫 발급 요청까지 미루면 "연동을
     * 켰는데 매 건 실패"를 고객 문의로 알게 된다. 여기서 막으면 켠 사람이 기동 실패로 즉시 안다.
     */
    public CashReceiptApiClient(String baseUrl, String issuePath, String cancelPath,
                                String merchantId, String secretKey) {
        this(baseUrl, issuePath, cancelPath, merchantId, secretKey, defaultRestTemplate());
    }

    /** 테스트 전용 — {@code MockRestServiceServer} 를 물릴 수 있도록 RestTemplate 을 주입받는다. */
    CashReceiptApiClient(String baseUrl, String issuePath, String cancelPath,
                         String merchantId, String secretKey, RestTemplate restTemplate) {
        requireConfigured(baseUrl, "app.cash-receipt.base-url");
        requireConfigured(issuePath, "app.cash-receipt.issue-path");
        requireConfigured(cancelPath, "app.cash-receipt.cancel-path");
        requireConfigured(merchantId, "app.cash-receipt.merchant-id");
        requireConfigured(secretKey, "app.cash-receipt.secret-key");

        String root = stripTrailingSlash(baseUrl.trim());
        this.issueUrl = root + withLeadingSlash(issuePath.trim());
        this.cancelUrl = root + withLeadingSlash(cancelPath.trim());
        this.merchantId = merchantId.trim();
        this.secretKey = secretKey.trim().getBytes(StandardCharsets.UTF_8);
        this.restTemplate = restTemplate;
    }

    private static RestTemplate defaultRestTemplate() {
        // 상위 보호(재시도·서킷)는 Resilience4j 가 담당한다 — 여기서는 무한 대기만 막는다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }

    private static void requireConfigured(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "현금영수증 연동이 켜져 있으나 설정이 비어 있습니다: " + key);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String withLeadingSlash(String value) {
        return value.startsWith("/") ? value : "/" + value;
    }

    /**
     * 발급 전문 전송.
     *
     * <p>반드시 <b>다른 빈에서</b> 호출해야 한다 — 자기호출은 프록시를 타지 않아 아래 두
     * 애노테이션이 무력화된다.
     *
     * @return 대행사가 부여한 승인번호
     * @throws IllegalStateException 업무 오류·응답 이상·연동 장애
     */
    @CircuitBreaker(name = CB_INSTANCE, fallbackMethod = "issueFallback")
    @Retry(name = CB_INSTANCE)
    public String issue(CashReceipt receipt) {
        String requestId = ISSUE_KEY_PREFIX + receipt.getPaymentId();
        long total = amountOf(receipt.getTotalAmount());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("requestId", requestId);
        body.put("orderId", receipt.getOrderId());
        body.put("tradeUsage", receipt.getPurpose().name());
        body.put("identifierType", receipt.getIdentifier().getType().name());
        // 원문을 싣는다 — 마스킹된 번호로는 국세청 발급이 되지 않는다(응답·로그는 masked() 를 쓴다).
        body.put("identifierNumber", receipt.getIdentifier().getValue());
        body.put("totalAmount", total);
        body.put("supplyAmount", amountOf(receipt.getSupplyAmount()));
        body.put("vatAmount", amountOf(receipt.getVatAmount()));

        String signature = sign(requestId, total, receipt.getIdentifier().getValue());
        Map<?, ?> response = post(issueUrl, body, requestId, signature, "발급");

        String approvalNumber = stringValue(response, "approvalNumber");
        if (approvalNumber == null || approvalNumber.isBlank()) {
            throw new IllegalStateException(
                    "현금영수증 발급 응답에 승인번호가 없습니다: paymentId=" + receipt.getPaymentId());
        }
        log.info("현금영수증 발급 전문 성공: paymentId={}, 식별번호={}, 승인번호={}",
                receipt.getPaymentId(), receipt.getIdentifier().masked(), approvalNumber);
        return approvalNumber;
    }

    /**
     * 취소 전문 전송.
     *
     * <p>반드시 <b>다른 빈에서</b> 호출해야 한다(위와 동일한 이유).
     */
    @CircuitBreaker(name = CB_INSTANCE, fallbackMethod = "cancelFallback")
    @Retry(name = CB_INSTANCE)
    public void cancel(CashReceipt receipt, String reason) {
        String approvalNumber = receipt.getApprovalNumber();
        if (approvalNumber == null || approvalNumber.isBlank()) {
            // 무엇을 취소할지 지정할 수 없다. 전문을 보내 봐야 반려되고, 그 사이 상태만 흔들린다.
            throw new IllegalStateException(
                    "승인번호 없는 현금영수증은 취소할 수 없습니다: paymentId=" + receipt.getPaymentId());
        }

        String requestId = CANCEL_KEY_PREFIX + receipt.getPaymentId();
        long total = amountOf(receipt.getTotalAmount());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("requestId", requestId);
        body.put("approvalNumber", approvalNumber);
        body.put("totalAmount", total);
        body.put("reason", reason);

        post(cancelUrl, body, requestId, sign(requestId, total, approvalNumber), "취소");
        log.info("현금영수증 취소 전문 성공: paymentId={}, 승인번호={}",
                receipt.getPaymentId(), approvalNumber);
    }

    private Map<?, ?> post(String url, Map<String, Object> body, String requestId,
                           String signature, String what) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", requestId);
        headers.set("X-Merchant-Id", merchantId);
        headers.set("X-Signature", signature);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);

            Map<?, ?> payload = response.getBody();
            String resultCode = stringValue(payload, "resultCode");
            if (!response.getStatusCode().is2xxSuccessful() || !SUCCESS_CODE.equals(resultCode)) {
                throw new IllegalStateException("현금영수증 " + what + " 실패: "
                        + resultCode + " - " + stringValue(payload, "message"));
            }
            return payload;

        } catch (HttpClientErrorException e) {
            // 4xx — 대행사 업무 오류(형식 오류, 이미 발급됨 등). 재시도·서킷 대상 아님.
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.error("현금영수증 {} 4xx: status={}, body={}", what, e.getStatusCode(), responseBody);
            throw new IllegalStateException(
                    "현금영수증 " + what + " 실패 (" + e.getStatusCode() + "): " + responseBody, e);
        }
    }

    /**
     * 원화는 소수 단위가 없다. 소수부가 남아 있으면 금액 계산이 어딘가에서 깨진 것이므로
     * {@code longValueExact} 가 예외로 드러내게 둔다 — 반올림으로 덮지 않는다.
     */
    private static long amountOf(BigDecimal amount) {
        return amount.longValueExact();
    }

    private String sign(String requestId, long totalAmount, String subject) {
        String canonical = String.join("|", merchantId, requestId, Long.toString(totalAmount), subject);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("현금영수증 전문 서명에 실패했습니다", e);
        }
    }

    private static String stringValue(Map<?, ?> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 서킷 오픈·재시도 소진 시의 종착점. 업무 오류는 사유를 보존해 그대로 올린다.
     *
     * <p>여기서 성공(승인번호 문자열)을 지어내면 국세청에는 없는 영수증이 발급 완료로 남는다.
     */
    @SuppressWarnings("unused") // Resilience4j 가 리플렉션으로 호출
    public String issueFallback(CashReceipt receipt, Throwable t) {
        throw wrap("발급", receipt, t);
    }

    @SuppressWarnings("unused") // Resilience4j 가 리플렉션으로 호출
    public void cancelFallback(CashReceipt receipt, String reason, Throwable t) {
        throw wrap("취소", receipt, t);
    }

    private IllegalStateException wrap(String what, CashReceipt receipt, Throwable t) {
        if (t instanceof IllegalStateException ise) {
            return ise;
        }
        log.error("현금영수증 {} 서킷 오픈 또는 재시도 소진: paymentId={}, cause={}",
                what, receipt.getPaymentId(), t.toString());
        return new IllegalStateException(
                "현금영수증 대행사 일시 장애로 " + what + "을(를) 완료할 수 없습니다: " + t.getMessage(), t);
    }
}
