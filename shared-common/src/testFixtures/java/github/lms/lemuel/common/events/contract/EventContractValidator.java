package github.lms.lemuel.common.events.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 이벤트 계약(contract-as-code) 검증기 — ADR 0024.
 *
 * <p>토픽별 JSON Schema({@code contracts/events/<topic>.schema.json})와 정본 샘플 페이로드
 * ({@code contracts/events/samples/<topic>.sample.json})를 단일 출처로 두고,
 * <ul>
 *   <li><b>프로듀서 계약 테스트</b>: 발행 어댑터가 실제로 조립한 outbox 페이로드를 스키마로 검증
 *       (필수 필드 삭제·타입 변경 시 빌드 실패)</li>
 *   <li><b>컨슈머 계약 테스트</b>: 정본 샘플을 실제 컨슈머 파싱 코드에 통과시켜 해석 결과 검증</li>
 * </ul>
 * 양측이 같은 스키마·샘플을 참조하므로, 계약 드리프트가 런타임(DLT/무성 null)이 아닌
 * 빌드 시점에 드러난다. Schema Registry(ADR 0022) 도입 전의 경량 단계.
 *
 * <p>스키마는 {@code additionalProperties: true} — optional 필드 <b>추가</b>는 언제나 허용(전방 호환),
 * required 필드의 삭제·이름변경·타입변경만 차단한다.
 */
public final class EventContractValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final ConcurrentMap<String, JsonSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    private EventContractValidator() {
    }

    /** 페이로드를 토픽 계약 스키마로 검증하고 위반 메시지 집합을 반환한다(비어 있으면 유효). */
    public static Set<String> validate(String topic, String payloadJson) {
        JsonSchema schema = SCHEMA_CACHE.computeIfAbsent(topic, EventContractValidator::loadSchema);
        JsonNode payload;
        try {
            payload = MAPPER.readTree(payloadJson);
        } catch (IOException e) {
            return Set.of("payload 가 JSON 이 아님: " + e.getMessage());
        }
        return schema.validate(payload).stream()
                .map(ValidationMessage::getMessage)
                .collect(Collectors.toSet());
    }

    /** 계약 위반이 있으면 위반 내역과 페이로드를 담아 AssertionError 를 던진다. */
    public static void assertValid(String topic, String payloadJson) {
        Set<String> violations = validate(topic, payloadJson);
        if (!violations.isEmpty()) {
            throw new AssertionError(
                    "이벤트 계약 위반 [" + topic + "]: " + violations + "\npayload=" + payloadJson);
        }
    }

    /** 토픽의 정본 샘플 페이로드(JSON 문자열)를 반환한다 — 컨슈머 계약 테스트의 입력. */
    public static String canonicalSample(String topic) {
        return readResource("contracts/events/samples/" + topic + ".sample.json");
    }

    private static JsonSchema loadSchema(String topic) {
        String path = "contracts/events/" + topic + ".schema.json";
        try (InputStream in = resourceStream(path)) {
            return FACTORY.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 스키마 로드 실패: " + path, e);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = resourceStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 리소스 로드 실패: " + path, e);
        }
    }

    private static InputStream resourceStream(String path) {
        InputStream in = EventContractValidator.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalArgumentException(
                    "계약 리소스 없음: " + path + " — 토픽 이름이 맞는지, 스키마/샘플이 등록됐는지 확인");
        }
        return in;
    }
}
