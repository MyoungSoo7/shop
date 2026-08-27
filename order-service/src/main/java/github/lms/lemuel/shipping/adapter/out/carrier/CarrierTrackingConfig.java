package github.lms.lemuel.shipping.adapter.out.carrier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 택배사 배송 조회 배선 — {@code app.carrier-tracking.enabled=true} 일 때만 뜬다.
 *
 * <p>기본값은 <b>꺼짐</b>이다. 이 기능의 정본은 우리 내부 이력이고 택배사 스캔은 얹는 것이므로,
 * 연동 계약이 없는 배포에서도 아무 설정 없이 정상 동작해야 한다.
 *
 * <p>켰는데 키가 없으면 기동을 막지 않고 WARN 을 남긴 채 꺼진 상태로 둔다
 * ({@link HttpCarrierTrackingAdapter#enabled()} 가 {@code false}). 이유는 그 클래스에 적혀 있다.
 */
@Configuration
@ConditionalOnProperty(name = "app.carrier-tracking.enabled", havingValue = "true")
public class CarrierTrackingConfig {

    private static final Logger log = LoggerFactory.getLogger(CarrierTrackingConfig.class);

    @Bean
    public HttpCarrierTrackingAdapter httpCarrierTrackingAdapter(
            @Value("${app.carrier-tracking.endpoint:}") String endpoint,
            @Value("${app.carrier-tracking.api-key:}") String apiKey,
            @Value("${app.carrier-tracking.carrier-codes:}") String carrierCodes) {

        if (endpoint.isBlank() || apiKey.isBlank()) {
            log.warn("택배사 배송 조회를 켰지만 설정이 비어 있습니다 — 조회는 꺼진 상태로 둡니다"
                    + " (app.carrier-tracking.endpoint / api-key). 내부 배송 이력은 그대로 표시됩니다.");
        }
        Map<String, String> codes = parseCarrierCodes(carrierCodes);
        if (codes.isEmpty() && !apiKey.isBlank()) {
            log.warn("택배사 코드 매핑이 비어 있습니다 — 모든 택배사가 조회 대상에서 제외됩니다"
                    + " (app.carrier-tracking.carrier-codes).");
        }
        return new HttpCarrierTrackingAdapter(endpoint, apiKey, codes);
    }

    /**
     * {@code "CJ대한통운:04,한진택배:05"} 형식을 표시명→코드 맵으로 바꾼다.
     *
     * <p>코드값을 소스에 내장하지 않는 이유는 {@link HttpCarrierTrackingAdapter} 에 적혀 있다.
     * 형식이 어긋난 항목은 조용히 버리지 않고 WARN 으로 남긴다 — 오타 하나로 그 택배사만 조회가
     * 안 되는데, 로그가 없으면 원인을 찾을 실마리가 없다.
     */
    static Map<String, String> parseCarrierCodes(String raw) {
        Map<String, String> codes = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return codes;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                log.warn("택배사 코드 매핑 형식 오류 — 무시합니다: {}", trimmed);
                continue;
            }
            codes.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return codes;
    }
}
