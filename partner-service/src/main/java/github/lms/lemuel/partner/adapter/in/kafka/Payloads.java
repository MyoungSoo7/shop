package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * <b>선택</b> 필드 접근 헬퍼.
 *
 * <p>필수 필드는 shared-common 의 {@code required*} 를 쓴다(누락 시 즉시 DLT). 여기 있는 것은
 * 계약상 없어도 되는 필드들이고, 없을 때 예외를 던지면 정상 이벤트가 DLT 로 간다.
 *
 * <p>다만 "없으면 0" 같은 기본값은 이 클래스에 두지 않았다 — 금액을 0 으로 채우면 화면에
 * 그럴듯한 숫자가 뜨고, 그게 틀렸다는 사실은 아무 데도 남지 않는다. 기본값을 줄지 말지는
 * 각 컨슈머가 자기 맥락에서 정한다.
 */
final class Payloads {

    private Payloads() {
    }

    static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static Long longOrNull(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    static BigDecimal decimalOrNull(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : new BigDecimal(value.asText());
    }

    /**
     * 존 없는 로컬시각. 계약 샘플은 {@code "2026-07-01T10:15:30"} 이지만, 프로듀서가 오프셋을
     * 붙여 보내는 경우도 받아 준다 — 그때 파싱 실패로 DLT 를 보내면 결제 한 건이 화면에서
     * 통째로 사라지는데, 그건 형식 문제이지 그 결제가 없었다는 뜻이 아니다.
     */
    static LocalDateTime localDateTimeOrNull(JsonNode payload, String field) {
        String raw = text(payload, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException withoutOffset) {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        }
    }
}
