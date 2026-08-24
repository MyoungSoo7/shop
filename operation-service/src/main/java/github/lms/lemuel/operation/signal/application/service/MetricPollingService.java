package github.lms.lemuel.operation.signal.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.signal.application.port.in.PollMetricsUseCase;
import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import github.lms.lemuel.operation.signal.application.port.out.MetricSourcePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 설정된 PromQL 쿼리들을 1회 폴링해 게이지 버킷에 적재한다.
 *
 * <p>한 쿼리 실패(네트워크·문법·결과 부재)가 나머지를 막지 않는다 — 개별 try 로 격리하고
 * 성공 건수만 집계한다. 결과 부재(empty)는 정상 신호일 수 있어(예: redis 다운으로 redis_up
 * 시계열 소멸) 경고 없이 건너뛴다 — 다만 Phase 3 에서 "값이 사라진 것"도 이상으로 볼 수 있게
 * 하려면 별도 처리가 필요하나 Phase 2 범위 밖.
 */
@Service
public class MetricPollingService implements PollMetricsUseCase {

    private static final Logger log = LoggerFactory.getLogger(MetricPollingService.class);

    private final MetricSourcePort metricSourcePort;
    private final RecordSignalUseCase recordSignalUseCase;
    private final Clock clock;
    private final Map<String, String> queries;

    public MetricPollingService(MetricSourcePort metricSourcePort,
                                RecordSignalUseCase recordSignalUseCase,
                                OpsProperties properties,
                                Clock clock) {
        this.metricSourcePort = metricSourcePort;
        this.recordSignalUseCase = recordSignalUseCase;
        this.clock = clock;
        this.queries = properties.getPrometheus().getQueries();
    }

    @Override
    public int pollOnce() {
        if (queries.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        int recorded = 0;
        for (Map.Entry<String, String> entry : queries.entrySet()) {
            String metricKey = entry.getKey();
            String promQl = entry.getValue();
            try {
                OptionalDouble value = metricSourcePort.queryInstant(promQl);
                if (value.isPresent()) {
                    recordSignalUseCase.recordGauge(metricKey, value.getAsDouble(), now);
                    recorded++;
                } else {
                    log.debug("Prometheus 결과 없음 — 건너뜀: metricKey={} query={}", metricKey, promQl);
                }
            } catch (Exception e) {
                log.warn("Prometheus 폴링 실패 (다음 주기 재시도): metricKey={} query={}", metricKey, promQl, e);
            }
        }
        log.debug("Prometheus 폴링 완료: {}/{} 쿼리 적재", recorded, queries.size());
        return recorded;
    }
}
