package github.lms.lemuel.operation.signal.adapter.out.prometheus;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.config.OpsProperties;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prometheus 응답 파싱 단위 테스트 — HTTP 통신 없이 parseFirstScalar 만 검증.
 * (통신 경로는 통합/운영에서 확인)
 */
class PrometheusMetricSourceAdapterTest {

    private PrometheusMetricSourceAdapter adapter() {
        OpsProperties properties = new OpsProperties();
        properties.getPrometheus().setBaseUrl("http://localhost:9090");
        return new PrometheusMetricSourceAdapter(properties, new ObjectMapper());
    }

    @Test
    void 정상_벡터_응답의_첫_샘플_값을_파싱한다() {
        String body = """
                {"status":"success","data":{"resultType":"vector",
                  "result":[{"metric":{"__name__":"x"},"value":[1720332000,"512"]}]}}
                """;
        assertThat(adapter().parseFirstScalar(body)).hasValue(512.0);
    }

    @Test
    void 소수_값도_파싱한다() {
        String body = """
                {"data":{"result":[{"value":[1720332000,"4.6"]}]}}
                """;
        assertThat(adapter().parseFirstScalar(body)).hasValue(4.6);
    }

    @Test
    void 결과_벡터가_비면_empty() {
        assertThat(adapter().parseFirstScalar("{\"data\":{\"result\":[]}}")).isEmpty();
    }

    @Test
    void NaN_은_empty_로_정규화한다() {
        // http.error.ratio 에서 분모 0 이면 Prometheus 가 "NaN" 을 돌려준다
        String body = "{\"data\":{\"result\":[{\"value\":[1720332000,\"NaN\"]}]}}";
        assertThat(adapter().parseFirstScalar(body)).isEmpty();
    }

    @Test
    void 무한대도_empty() {
        assertThat(adapter().parseFirstScalar("{\"data\":{\"result\":[{\"value\":[1,\"+Inf\"]}]}}")).isEmpty();
    }

    @Test
    void null_또는_공백_또는_깨진_JSON_은_empty() {
        assertThat(adapter().parseFirstScalar(null)).isEmpty();
        assertThat(adapter().parseFirstScalar("")).isEmpty();
        assertThat(adapter().parseFirstScalar("not json")).isEmpty();
        assertThat(adapter().parseFirstScalar("{\"data\":{}}")).isEmpty();
    }
}
