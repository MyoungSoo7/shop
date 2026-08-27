package github.lms.lemuel.shipping.adapter.out.carrier;

import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 중계 어댑터.
 *
 * <p>이 테스트가 지키는 핵심은 <b>키가 응답·사유 문구로 새지 않는다</b>는 것과,
 * <b>모르는 택배사에 아무 코드나 넣어 보지 않는다</b>는 것이다. 후자는 남의 운송장을 조회하는
 * 일이 될 수 있다.
 */
class HttpCarrierTrackingAdapterTest {

    private static final String ENDPOINT = "https://carrier.example.com/api/tracking";
    private static final String KEY = "test-key-not-a-real-secret";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private HttpCarrierTrackingAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        adapter = new HttpCarrierTrackingAdapter(ENDPOINT, KEY,
                Map.of("CJ대한통운", "04", "한진택배", "05"), restTemplate);
    }

    @Test
    @DisplayName("엔드포인트나 키가 비면 꺼진 상태 — 기동은 막지 않는다")
    void disabledWhenUnconfigured() {
        assertThat(new HttpCarrierTrackingAdapter("", KEY, Map.of(), restTemplate).enabled()).isFalse();
        assertThat(new HttpCarrierTrackingAdapter(ENDPOINT, "", Map.of(), restTemplate).enabled()).isFalse();
        assertThat(new HttpCarrierTrackingAdapter(null, null, null, restTemplate).enabled()).isFalse();
        assertThat(adapter.enabled()).isTrue();
    }

    @Test
    @DisplayName("코드 매핑이 없는 택배사는 조회를 시도조차 하지 않는다")
    void unmappedCarrierIsNotProbed() {
        CarrierTrackingPort.Result result = adapter.fetch("우체국택배", "1234");

        assertThat(result.available()).isFalse();
        assertThat(result.scans()).isEmpty();
        assertThat(result.unavailableReason()).contains("우체국택배").doesNotContain(KEY);
        server.verify(); // 아무 요청도 나가지 않았다
    }

    @Test
    @DisplayName("운송장의 하이픈을 빼고 키·코드와 함께 질의한다")
    void buildsQueryWithNormalizedInvoice() {
        server.expect(requestTo(ENDPOINT + "?t_key=" + KEY + "&t_code=04&t_invoice=123456789012"))
                .andRespond(withSuccess("{\"trackingDetails\":[]}", MediaType.APPLICATION_JSON));

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "1234-5678-9012");

        assertThat(result.available()).isTrue();
        assertThat(result.scans()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("epoch millis 를 발생 시각으로 쓰고, 단계 번호를 우리 상태로 옮긴다")
    void mapsScans() {
        long millis = LocalDateTime.of(2026, 8, 20, 9, 30)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String body = """
                {"trackingDetails":[
                  {"time":%d,"kind":"집화처리","where":"강남집배점","level":2},
                  {"time":%d,"kind":"간선상차","where":"동서울허브","level":3},
                  {"time":%d,"kind":"배달완료","where":"강남","level":6}
                ]}""".formatted(millis, millis + 3600_000, millis + 7200_000);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(ENDPOINT)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");

        assertThat(result.available()).isTrue();
        assertThat(result.scans()).extracting(CarrierTrackingPort.Scan::status).containsExactly(
                ShippingStatus.SHIPPED, ShippingStatus.IN_TRANSIT, ShippingStatus.DELIVERED);
        assertThat(result.scans().get(0).description()).isEqualTo("집화처리");
        assertThat(result.scans().get(0).location()).isEqualTo("강남집배점");
        assertThat(result.scans().get(0).occurredAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 20, 9, 30));
    }

    @Test
    @DisplayName("epoch 가 없으면 문자열 시각을 쓴다")
    void fallsBackToTextTime() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(ENDPOINT)))
                .andRespond(withSuccess(
                        "{\"trackingDetails\":[{\"timeString\":\"2026-08-20 09:30:00\",\"kind\":\"집화처리\"}]}",
                        MediaType.APPLICATION_JSON));

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");

        assertThat(result.scans()).hasSize(1);
        assertThat(result.scans().get(0).occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 20, 9, 30));
    }

    @Test
    @DisplayName("시각이나 설명이 없는 줄은 버린다 — 지금 시각을 붙이면 순서가 뒤집힌다")
    void dropsRowsWithoutTimeOrKind() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(ENDPOINT)))
                .andRespond(withSuccess("""
                        {"trackingDetails":[
                          {"kind":"시각 없음"},
                          {"timeString":"2026-08-20 09:30:00"},
                          {"timeString":"엉터리","kind":"형식 오류"},
                          {"timeString":"2026-08-20 10:00:00","kind":"정상"}
                        ]}""", MediaType.APPLICATION_JSON));

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");

        assertThat(result.scans()).extracting(CarrierTrackingPort.Scan::description)
                .containsExactly("정상");
    }

    @Test
    @DisplayName("중계사가 200 으로 돌려준 오류도 실패로 본다 — 사유는 우리 문구로 바꾼다")
    void treatsMissingDetailsAsFailure() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(ENDPOINT)))
                .andRespond(withSuccess("{\"status\":\"400\",\"msg\":\"인증키가 유효하지 않습니다\"}",
                        MediaType.APPLICATION_JSON));

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");

        assertThat(result.available()).isFalse();
        assertThat(result.scans()).isEmpty();
        assertThat(result.unavailableReason()).doesNotContain("인증키").doesNotContain(KEY);
    }

    @Test
    @DisplayName("HTTP 오류는 예외가 아니라 사유로 돌아온다 — 배송 화면을 500 으로 만들지 않는다")
    void httpErrorBecomesReason() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(ENDPOINT)))
                .andRespond(withServerError());

        CarrierTrackingPort.Result result = adapter.fetch("CJ대한통운", "123456789012");

        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).doesNotContain(KEY);
    }

    @Test
    @DisplayName("꺼진 어댑터는 호출해도 요청을 보내지 않는다")
    void disabledAdapterDoesNotCall() {
        HttpCarrierTrackingAdapter off = new HttpCarrierTrackingAdapter("", "", Map.of(), restTemplate);

        CarrierTrackingPort.Result result = off.fetch("CJ대한통운", "123456789012");

        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo(DisabledCarrierTrackingAdapter.REASON);
        server.verify();
    }
}
