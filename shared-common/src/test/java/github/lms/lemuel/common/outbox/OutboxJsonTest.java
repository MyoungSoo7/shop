package github.lms.lemuel.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATA-STANDARD N5(금액 wire 표현) 가드 — outbox payload 의 BigDecimal 은 반드시
 * {@code toPlainString()} 문자열로 나가야 한다.
 */
class OutboxJsonTest {

    private final ObjectMapper mapper = OutboxJson.mapper();

    @Test
    @DisplayName("BigDecimal 은 JSON 문자열로 직렬화된다(number 금지)")
    void bigDecimalSerializedAsString() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", new BigDecimal("42500.00"));

        String json = mapper.writeValueAsString(payload);

        assertThat(json).isEqualTo("{\"amount\":\"42500.00\"}");
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("amount").isTextual()).isTrue();
        // 소비측 파싱 경로(IdempotentEventConsumer#requiredDecimal)와 동일하게 값이 보존된다
        assertThat(new BigDecimal(node.get("amount").asText()))
                .isEqualByComparingTo(new BigDecimal("42500.00"));
    }

    @Test
    @DisplayName("과학적 표기(1E+3)로 새지 않는다 — toString() 이 아니라 toPlainString()")
    void noScientificNotation() throws Exception {
        String json = mapper.writeValueAsString(Map.of("amount", new BigDecimal("1E+3")));

        assertThat(json).isEqualTo("{\"amount\":\"1000\"}");
    }

    @Test
    @DisplayName("scale 이 보존된다 — 0.10 이 0.1 로 뭉개지지 않음")
    void scalePreserved() throws Exception {
        String json = mapper.writeValueAsString(Map.of("rate", new BigDecimal("0.10")));

        assertThat(json).isEqualTo("{\"rate\":\"0.10\"}");
    }

    @Test
    @DisplayName("정수 ID·날짜 등 비금액 필드의 표현은 바뀌지 않는다")
    void nonMoneyFieldsUnchanged() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", 9001L);
        payload.put("dueDate", LocalDate.of(2026, 7, 10).toString());

        String json = mapper.writeValueAsString(payload);

        assertThat(json).isEqualTo("{\"settlementId\":9001,\"dueDate\":\"2026-07-10\"}");
    }
}
