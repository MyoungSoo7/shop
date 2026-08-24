package github.lms.lemuel.common.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 도메인 중립 비전 추출 클라이언트의 계약 — "이미지+프롬프트 → 구조화 JSON" 만 책임진다.
 *
 * <p>핵심 원칙(무폴백): 호출 실패·빈 응답·형식 파손은 전부 {@link VisionExtractionException} 이다.
 * 프롬프트·필드 해석은 호출 도메인 소유 — 여기서는 봉투 해체까지만 검증한다.
 */
class VisionExtractionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Gemini generateContent 응답 봉투 — candidates[0].content.parts[0].text 에 내부 JSON 이 온다. */
    private static String envelope(String innerJson) {
        try {
            return MAPPER.writeValueAsString(Map.of("candidates", List.of(
                    Map.of("content", Map.of("parts", List.of(Map.of("text", innerJson)))))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static VisionExtractionClient client(RestClient.Builder builder) {
        return new VisionExtractionClient(
                "https://generativelanguage.googleapis.com", "test-key", "gemini-2.5-flash", 1024, builder);
    }

    // ── 요청 본문 ──

    @Test
    @DisplayName("요청 본문에 프롬프트·이미지 inline_data·JSON 강제·출력 토큰 상한이 실린다")
    void buildsInlineImageRequest() {
        Map<String, Object> body = VisionExtractionClient.buildBody(
                "영수증을 읽어라", new byte[]{1, 2, 3}, "image/jpeg", 512);

        String json = body.toString();
        assertThat(json).contains("영수증을 읽어라");
        assertThat(json).contains("inline_data").contains("image/jpeg").contains("AQID"); // base64(1,2,3)
        assertThat(json).contains("application/json");   // responseMimeType — 구조화 응답 강제
        assertThat(json).contains("maxOutputTokens").contains("512");
    }

    @Test
    @DisplayName("콘텐츠 타입 누락 시 image/png, 본문 누락 시 빈 바이트로 방어한다")
    void buildBodyDefaults() {
        String json = VisionExtractionClient.buildBody("p", null, null, null).toString();

        assertThat(json).contains("image/png");
        assertThat(json).doesNotContain("maxOutputTokens");
    }

    // ── 응답 봉투 해체 ──

    @Test
    @DisplayName("봉투 안의 JSON 객체를 그대로 돌려준다")
    void parsesEnvelope() {
        JsonNode fields = VisionExtractionClient.parseEnvelope(
                envelope("{\"merchantName\":\"김밥천국\",\"totalAmount\":\"12000\"}"), MAPPER);

        assertThat(fields.path("merchantName").asText()).isEqualTo("김밥천국");
        assertThat(fields.path("totalAmount").asText()).isEqualTo("12000");
    }

    @Test
    @DisplayName("코드펜스로 감싸 오는 응답도 벗겨서 읽는다 (모델의 흔한 습관)")
    void stripsCodeFence() {
        JsonNode fields = VisionExtractionClient.parseEnvelope(
                envelope("```json\n{\"totalAmount\":\"9900\"}\n```"), MAPPER);

        assertThat(fields.path("totalAmount").asText()).isEqualTo("9900");
    }

    @Test
    @DisplayName("빈 응답·후보 없음·빈 텍스트는 전부 추출 실패 — 지어내지 않는다")
    void emptyResponses() {
        assertThatThrownBy(() -> VisionExtractionClient.parseEnvelope(null, MAPPER))
                .isInstanceOf(VisionExtractionException.class);
        assertThatThrownBy(() -> VisionExtractionClient.parseEnvelope("{}", MAPPER))
                .isInstanceOf(VisionExtractionException.class);
        assertThatThrownBy(() -> VisionExtractionClient.parseEnvelope(envelope("   "), MAPPER))
                .isInstanceOf(VisionExtractionException.class);
    }

    @Test
    @DisplayName("JSON 이 아니거나 객체가 아니면 추출 실패")
    void malformedInnerText() {
        assertThatThrownBy(() -> VisionExtractionClient.parseEnvelope(envelope("읽을 수 없습니다"), MAPPER))
                .isInstanceOf(VisionExtractionException.class);
        assertThatThrownBy(() -> VisionExtractionClient.parseEnvelope(envelope("[1,2,3]"), MAPPER))
                .isInstanceOf(VisionExtractionException.class);
    }

    // ── 구성 ──

    @Test
    @DisplayName("API 키가 없으면 미구성 — 호출 도메인이 503 으로 끊을 근거")
    void notConfiguredWithoutKey() {
        VisionExtractionClient client = new VisionExtractionClient(
                "https://generativelanguage.googleapis.com", " ", "gemini-2.5-flash", null,
                RestClient.builder());

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.modelName()).isEqualTo("gemini-2.5-flash");
    }

    // ── HTTP 호출 ──

    @Test
    @DisplayName("generateContent 를 모델 경로·x-goog-api-key 로 호출하고 봉투를 해체해 돌려준다")
    void extractsOverHttp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess(envelope("{\"totalAmount\":\"12000\",\"confidence\":\"0.91\"}"),
                        MediaType.APPLICATION_JSON));

        JsonNode fields = client(builder).extractJson(new byte[]{1}, "image/png", "영수증을 읽어라");

        assertThat(fields.path("totalAmount").asText()).isEqualTo("12000");
        server.verify();
    }

    @Test
    @DisplayName("HTTP 실패는 폴백 없이 추출 실패로 끊는다")
    void httpFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client(builder).extractJson(new byte[]{1}, "image/png", "p"))
                .isInstanceOf(VisionExtractionException.class);
    }
}
