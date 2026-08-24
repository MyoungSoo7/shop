package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 현금영수증 대행 전문 클라이언트 — 서명·전문 필드·응답 판정.
 *
 * <p>레거시 커머스(ssgb2e {@code OrderCashReceiptServiceImpl})가 PG 에 HMAC 서명한 전문을 직접
 * POST 하던 자리를, 같은 형태의 범용 전문으로 옮겼다. 특정 대행사에 고정된 필드명이 아니라
 * <b>설정으로 엔드포인트를 갈아끼우는</b> 구조라, 대행사가 바뀌면 매핑만 손보면 된다.
 */
class CashReceiptApiClientTest {

    private static final String BASE_URL = "https://pg.example.com";
    private static final String ISSUE_PATH = "/v1/cash-receipts";
    private static final String CANCEL_PATH = "/v1/cash-receipts/cancel";
    private static final String MERCHANT_ID = "LEMUEL";
    private static final String SECRET = "cash-receipt-secret";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);

    private MockRestServiceServer server;
    private CashReceiptApiClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new CashReceiptApiClient(BASE_URL, ISSUE_PATH, CANCEL_PATH, MERCHANT_ID, SECRET, restTemplate);
    }

    private CashReceipt requested() {
        return CashReceipt.request(77L, 501L, 9L, "BANK_TRANSFER", new BigDecimal("11000"),
                CashReceiptPurpose.INCOME_DEDUCTION,
                CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "010-1234-5678"),
                NOW);
    }

    private CashReceipt issued() {
        CashReceipt receipt = requested();
        receipt.markIssued("NTS-0001", NOW);
        return receipt;
    }

    /** 클라이언트와 독립적으로 계산한 기대 서명 — 구현을 그대로 베끼지 않기 위해 테스트가 직접 만든다. */
    private static String expectedSignature(String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("발급 전문 — 엔드포인트·서명·멱등키·금액 분해가 규격대로 실린다")
    void issueSendsSignedRequest() {
        String signature = expectedSignature(MERCHANT_ID, "CR-77", "11000", "01012345678");

        server.expect(requestTo(BASE_URL + ISSUE_PATH))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header("Idempotency-Key", "CR-77"))
                .andExpect(header("X-Merchant-Id", MERCHANT_ID))
                .andExpect(header("X-Signature", signature))
                .andExpect(jsonPath("$.requestId").value("CR-77"))
                .andExpect(jsonPath("$.orderId").value(501))
                .andExpect(jsonPath("$.tradeUsage").value("INCOME_DEDUCTION"))
                .andExpect(jsonPath("$.identifierType").value("MOBILE"))
                .andExpect(jsonPath("$.totalAmount").value(11000))
                .andExpect(jsonPath("$.supplyAmount").value(10000))
                .andExpect(jsonPath("$.vatAmount").value(1000))
                .andRespond(withSuccess("{\"resultCode\":\"0000\",\"approvalNumber\":\"NTS-77001\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.issue(requested())).isEqualTo("NTS-77001");
        server.verify();
    }

    /**
     * 국세청은 마스킹된 번호로 발급할 수 없다. 응답·로그에는 마스킹을 쓰되 전문에는 원문이
     * 실려야 한다 — 이 둘을 뒤바꾸면 전 건이 반려된다.
     */
    @Test
    @DisplayName("전문에는 식별번호 원문이 실린다(마스킹은 응답·로그 전용)")
    void issueSendsRawIdentifier() {
        server.expect(requestTo(BASE_URL + ISSUE_PATH))
                .andExpect(jsonPath("$.identifierNumber").value("01012345678"))
                .andRespond(withSuccess("{\"resultCode\":\"0000\",\"approvalNumber\":\"NTS-1\"}",
                        MediaType.APPLICATION_JSON));

        client.issue(requested());
        server.verify();
    }

    /** 같은 결제를 두 번 태워도 대행사가 최초 결과를 돌려주도록, 멱등키는 결제 id 에서 결정된다. */
    @Test
    @DisplayName("멱등키는 결제 id 로 결정된다 — 재시도가 이중 발급이 되지 않는다")
    void idempotencyKeyIsDeterministic() {
        for (int i = 0; i < 2; i++) {
            server.expect(header("Idempotency-Key", "CR-77"))
                    .andRespond(withSuccess("{\"resultCode\":\"0000\",\"approvalNumber\":\"NTS-1\"}",
                            MediaType.APPLICATION_JSON));
        }

        assertThat(client.issue(requested())).isEqualTo("NTS-1");
        assertThat(client.issue(requested())).isEqualTo("NTS-1");
        server.verify();
    }

    @Test
    @DisplayName("업무 오류 코드는 예외로 올린다 — 승인번호 없이 발급 성공으로 넘기지 않는다")
    void nonZeroResultCodeThrows() {
        server.expect(requestTo(BASE_URL + ISSUE_PATH))
                .andRespond(withSuccess(
                        "{\"resultCode\":\"9001\",\"message\":\"이미 발급된 거래\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.issue(requested()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9001")
                .hasMessageContaining("이미 발급된 거래");
    }

    @Test
    @DisplayName("2xx 인데 승인번호가 없으면 실패로 본다")
    void missingApprovalNumberThrows() {
        server.expect(requestTo(BASE_URL + ISSUE_PATH))
                .andRespond(withSuccess("{\"resultCode\":\"0000\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.issue(requested()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("승인번호");
    }

    @Test
    @DisplayName("4xx 는 대행사 업무 오류 — 본문을 그대로 담아 올린다")
    void clientErrorThrowsWithBody() {
        server.expect(requestTo(BASE_URL + ISSUE_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"식별번호 형식 오류\"}"));

        assertThatThrownBy(() -> client.issue(requested()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("식별번호 형식 오류");
    }

    @Test
    @DisplayName("취소 전문 — 승인번호로 서명하고 별도 멱등키를 쓴다")
    void cancelSendsSignedRequest() {
        String signature = expectedSignature(MERCHANT_ID, "CRX-77", "11000", "NTS-0001");

        server.expect(requestTo(BASE_URL + CANCEL_PATH))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "CRX-77"))
                .andExpect(header("X-Signature", signature))
                .andExpect(jsonPath("$.approvalNumber").value("NTS-0001"))
                .andExpect(jsonPath("$.reason").value("전액 환불"))
                .andRespond(withSuccess("{\"resultCode\":\"0000\"}", MediaType.APPLICATION_JSON));

        client.cancel(issued(), "전액 환불");
        server.verify();
    }

    @Test
    @DisplayName("취소 실패 코드는 예외로 올린다")
    void cancelNonZeroResultCodeThrows() {
        server.expect(requestTo(BASE_URL + CANCEL_PATH))
                .andRespond(withSuccess(
                        "{\"resultCode\":\"9100\",\"message\":\"취소 가능 기간 초과\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.cancel(issued(), "전액 환불"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("취소 가능 기간 초과");
    }

    /** 승인번호 없이 취소 전문을 보내면 대행사에서 무엇을 취소할지 알 수 없다 — 호출 자체를 막는다. */
    @Test
    @DisplayName("승인번호 없는 취소는 호출 전에 거부한다")
    void cancelWithoutApprovalNumberRejected() {
        assertThatThrownBy(() -> client.cancel(requested(), "전액 환불"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("승인번호");
    }

    /** 자격증명이 비면 서명이 무의미해진다 — 생성 시점에 막아 기동 실패로 드러낸다. */
    @Test
    @DisplayName("자격증명이 비어 있으면 생성 시점에 거부한다")
    void blankCredentialsRejectedAtConstruction() {
        assertThatThrownBy(() -> new CashReceiptApiClient(BASE_URL, ISSUE_PATH, CANCEL_PATH, "", SECRET))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CashReceiptApiClient(BASE_URL, ISSUE_PATH, CANCEL_PATH, MERCHANT_ID, " "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CashReceiptApiClient("", ISSUE_PATH, CANCEL_PATH, MERCHANT_ID, SECRET))
                .isInstanceOf(IllegalStateException.class);
    }
}
