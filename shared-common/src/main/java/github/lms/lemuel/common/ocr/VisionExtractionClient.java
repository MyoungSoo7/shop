package github.lms.lemuel.common.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 도메인 중립 비전 추출 클라이언트 — "이미지+프롬프트 → 구조화 JSON 객체" 까지만 책임진다 (ADR 0036).
 *
 * <p>Gemini(Generative Language API) generateContent 를 {@code inline_data}(base64) +
 * {@code responseMimeType=application/json} 으로 호출하고, 응답 봉투(candidates[0].content.parts[0].text)
 * 를 해체해 내부 JSON 객체를 돌려준다. <b>프롬프트와 필드 해석은 호출 도메인 소유</b> — 세금계산서·영수증이
 * 무엇을 어떻게 읽을지는 도메인 지식이라 여기로 올리지 않는다.
 *
 * <p><b>스프링 빈이 아니다</b>({@code common.pdf} 의 {@code GhostscriptService} 와 같은 결) — 각 서비스
 * 어댑터가 자기 프로퍼티(키·모델·baseUrl)로 생성한다. 제한 스캔 서비스의 {@code @Import} 문제를 원천
 * 회피하고 키·비용 통제를 서비스별로 유지한다.
 *
 * <p><b>무폴백</b>: 호출 실패·빈 응답·형식 파손은 전부 {@link VisionExtractionException} 이다.
 */
public class VisionExtractionClient {

    private final String apiKey;
    private final String model;
    private final Integer maxOutputTokens;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisionExtractionClient(String baseUrl, String apiKey, String model, Integer maxOutputTokens) {
        this(baseUrl, apiKey, model, maxOutputTokens, RestClient.builder());
    }

    /** 테스트·커스터마이즈용 — 빌더를 밖에서 주입한다 ({@code MockRestServiceServer.bindTo(builder)}). */
    public VisionExtractionClient(String baseUrl, String apiKey, String model, Integer maxOutputTokens,
                                  RestClient.Builder builder) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** 호출 가능한 구성인가(API 키 주입 여부) — 미구성이면 호출 도메인이 503 으로 끊는다. */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** 감사·재현용 모델 식별자 — 추출 결과 행에 함께 저장하라. */
    public String modelName() {
        return model;
    }

    /**
     * 이미지에서 프롬프트가 요구한 JSON 객체를 추출한다.
     *
     * @throws VisionExtractionException 호출 실패·빈 응답·JSON 객체가 아닌 응답
     */
    public JsonNode extractJson(byte[] content, String contentType, String prompt) {
        String response;
        try {
            response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .header("content-type", "application/json")
                    .body(buildBody(prompt, content, contentType, maxOutputTokens))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new VisionExtractionException("비전 추출 호출에 실패했습니다.", e);
        }
        return parseEnvelope(response, objectMapper);
    }

    /** generateContent 요청 본문 — 프롬프트 + 이미지 inline_data + JSON 강제 (+ 출력 토큰 상한). */
    static Map<String, Object> buildBody(String prompt, byte[] content, String contentType,
                                         Integer maxOutputTokens) {
        String base64 = Base64.getEncoder().encodeToString(content == null ? new byte[0] : content);
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            generationConfig.put("maxOutputTokens", maxOutputTokens);
        }
        return Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(
                        Map.of("text", prompt),
                        Map.of("inline_data", Map.of(
                                "mime_type", contentType == null ? "image/png" : contentType,
                                "data", base64))))),
                "generationConfig", generationConfig);
    }

    /**
     * 응답 봉투(candidates[0].content.parts[0].text) 안의 JSON 객체를 꺼낸다.
     * 코드펜스(```json)로 감싸 오는 흔한 습관은 벗겨서 읽는다.
     */
    static JsonNode parseEnvelope(String response, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            throw new VisionExtractionException("비전 추출 응답 파싱에 실패했습니다.", e);
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new VisionExtractionException("비전 추출이 빈 응답을 반환했습니다.");
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        String text = (parts.isArray() && !parts.isEmpty()) ? parts.get(0).path("text").asText("") : "";
        if (text.isBlank()) {
            throw new VisionExtractionException("비전 추출이 빈 응답을 반환했습니다.");
        }

        JsonNode fields;
        try {
            fields = mapper.readTree(stripCodeFence(text));
        } catch (Exception e) {
            throw new VisionExtractionException("비전 추출 응답이 JSON 형식이 아닙니다.", e);
        }
        if (!fields.isObject()) {
            throw new VisionExtractionException("비전 추출 응답이 JSON 객체가 아닙니다.");
        }
        return fields;
    }

    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        String body = firstLineEnd < 0 ? "" : trimmed.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        return (closing < 0 ? body : body.substring(0, closing)).trim();
    }
}
