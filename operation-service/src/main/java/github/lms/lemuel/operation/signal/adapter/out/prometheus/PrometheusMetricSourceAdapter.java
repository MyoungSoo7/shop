package github.lms.lemuel.operation.signal.adapter.out.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.signal.application.port.out.MetricSourcePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * Prometheus HTTP API 인스턴트 쿼리 어댑터 ({@code GET /api/v1/query?query=...}).
 *
 * <p>{@code app.ops.prometheus.enabled=true} 일 때만 활성. 비활성 시 {@link NoOpMetricSourceAdapter}
 * 가 대신 주입되어 폴러가 항상 안전하게 empty 를 받는다 (로컬/테스트에서 Prometheus 불필요).
 *
 * <p>결과 벡터 첫 샘플의 스칼라 값을 파싱한다. 시계열 부재(빈 result), NaN/Inf(예: 0 분모 비율),
 * 통신·파싱 오류는 모두 empty 로 정규화 — 폴러가 "건너뜀"으로 처리한다.
 */
@Component
@ConditionalOnProperty(name = "app.ops.prometheus.enabled", havingValue = "true")
public class PrometheusMetricSourceAdapter implements MetricSourcePort {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricSourceAdapter.class);

    private final RestClient restClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public PrometheusMetricSourceAdapter(OpsProperties properties,
                                         com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        OpsProperties.Prometheus cfg = properties.getPrometheus();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
        log.info("Prometheus 폴링 활성: baseUrl={}", cfg.getBaseUrl());
    }

    @Override
    public OptionalDouble queryInstant(String promQl) {
        String body = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/query").queryParam("query", promQl).build())
                .retrieve()
                .body(String.class);
        return parseFirstScalar(body);
    }

    /** {"data":{"result":[{"value":[<ts>,"<val>"]}]}} 첫 샘플 값 파싱. 부재/비유한수 → empty. */
    OptionalDouble parseFirstScalar(String body) {
        if (body == null || body.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            JsonNode result = objectMapper.readTree(body).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return OptionalDouble.empty();
            }
            JsonNode value = result.get(0).path("value");
            if (!value.isArray() || value.size() < 2) {
                return OptionalDouble.empty();
            }
            double parsed = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(parsed) ? OptionalDouble.of(parsed) : OptionalDouble.empty();
        } catch (Exception e) {
            log.debug("Prometheus 응답 파싱 실패", e);
            return OptionalDouble.empty();
        }
    }
}
