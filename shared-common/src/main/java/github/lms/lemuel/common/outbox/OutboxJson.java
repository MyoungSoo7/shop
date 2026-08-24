package github.lms.lemuel.common.outbox;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Outbox 이벤트 payload 전용 JSON 직렬화 — DATA-STANDARD N5(금액).
 *
 * <p><b>왜 별도 ObjectMapper 인가</b>: 금액을 JSON number 로 실으면 소비측 언어/파서에 따라
 * 정밀도가 소실된다(IEEE-754 double 은 2^53 초과 정수·소수 스케일을 보존하지 못하고,
 * JS {@code JSON.parse} 는 모든 number 를 double 로 읽는다). N5 는 wire 표현을
 * {@code BigDecimal.toPlainString()} 문자열로 고정한다.
 *
 * <p><b>왜 전역 ObjectMapper 를 바꾸지 않는가</b>: 레거시 {@code jacksonLegacyObjectMapper} 는
 * Redis 캐시·HTTP 클라이언트 등 82곳이 공유한다. 거기까지 금액 표현을 바꾸면 blast radius 가
 * 이벤트 계약 밖으로 번지므로, outbox payload 조립 경로에만 한정한다
 * ({@code JacksonCompatConfig#outboxObjectMapper}).
 *
 * <p><b>호환성</b>: 소비측은 모두 {@code new BigDecimal(node.asText())} 로 파싱하므로
 * (shared-common {@code IdempotentEventConsumer#requiredDecimal}) 숫자·문자열 양쪽을 받는다.
 * 즉 프로듀서 선행 배포가 안전하다.
 */
public final class OutboxJson {

    private static final ObjectMapper MAPPER = create();

    private OutboxJson() {
    }

    /** outbox payload 직렬화용 ObjectMapper(금액=plain string). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    static ObjectMapper create() {
        SimpleModule money = new SimpleModule("outbox-money");
        money.addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer());
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .registerModule(money);
    }

    /**
     * {@code 1E+3} 같은 과학적 표기를 막기 위해 {@code toString()} 이 아닌
     * {@code toPlainString()} 을 쓴다. {@code setScale} 로 만들어진 값도 그대로 보존된다.
     */
    private static final class PlainStringBigDecimalSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
