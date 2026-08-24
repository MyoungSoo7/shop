package github.lms.lemuel.operation.signal.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import github.lms.lemuel.operation.signal.application.port.out.MetricSourcePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricPollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-07T06:03:00Z");

    @Mock
    MetricSourcePort metricSourcePort;
    @Mock
    RecordSignalUseCase recordSignalUseCase;

    private MetricPollingService service(Map<String, String> queries) {
        OpsProperties properties = new OpsProperties();
        properties.getPrometheus().setQueries(queries);
        return new MetricPollingService(metricSourcePort, recordSignalUseCase, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 각_쿼리_결과를_해당_metricKey_게이지로_적재한다() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("kafka.lag.max", "max(kafka_consumergroup_lag)");
        queries.put("redis.up", "min(redis_up)");
        when(metricSourcePort.queryInstant("max(kafka_consumergroup_lag)")).thenReturn(OptionalDouble.of(512));
        when(metricSourcePort.queryInstant("min(redis_up)")).thenReturn(OptionalDouble.of(1));

        int recorded = service(queries).pollOnce();

        assertThat(recorded).isEqualTo(2);
        verify(recordSignalUseCase).recordGauge("kafka.lag.max", 512.0, NOW);
        verify(recordSignalUseCase).recordGauge("redis.up", 1.0, NOW);
    }

    @Test
    void 결과_없는_쿼리는_건너뛰고_적재하지_않는다() {
        Map<String, String> queries = Map.of("redis.up", "min(redis_up)");
        when(metricSourcePort.queryInstant("min(redis_up)")).thenReturn(OptionalDouble.empty());

        int recorded = service(queries).pollOnce();

        assertThat(recorded).isZero();
        verify(recordSignalUseCase, never()).recordGauge(any(), anyDouble(), any());
    }

    @Test
    void 한_쿼리_예외가_나머지를_막지_않는다() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("bad", "boom(");
        queries.put("kafka.lag.max", "max(kafka_consumergroup_lag)");
        when(metricSourcePort.queryInstant("boom(")).thenThrow(new RuntimeException("query error"));
        when(metricSourcePort.queryInstant("max(kafka_consumergroup_lag)")).thenReturn(OptionalDouble.of(7));

        int recorded = service(queries).pollOnce();

        assertThat(recorded).isEqualTo(1);
        verify(recordSignalUseCase).recordGauge(eq("kafka.lag.max"), eq(7.0), any());
    }

    @Test
    void 쿼리가_없으면_0() {
        assertThat(service(Map.of()).pollOnce()).isZero();
    }
}
