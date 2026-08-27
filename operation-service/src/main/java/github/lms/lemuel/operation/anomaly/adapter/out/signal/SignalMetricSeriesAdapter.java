package github.lms.lemuel.operation.anomaly.adapter.out.signal;

import github.lms.lemuel.operation.anomaly.application.port.out.LoadMetricSeriesPort;
import github.lms.lemuel.operation.anomaly.domain.MetricPoint;
import github.lms.lemuel.operation.signal.application.port.in.QueryMetricSeriesUseCase;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 시계열 공급자로 signal 을 쓰는 어댑터 — signal 의 공개 창구
 * ({@link QueryMetricSeriesUseCase})만 호출하고, 결과를 anomaly 의 {@link MetricPoint} 로 옮긴다.
 *
 * <p>이 클래스가 존재하는 이유가 곧 번역이다. 이전 구현은 signal 의 JPA 엔티티와 리포지토리를
 * 직접 들고 있어서, 이름만 어댑터일 뿐 실제로는 남의 저장소에 손을 넣고 있었다.
 * 지금은 signal 의 적재 방식이 바뀌어도 이 파일 하나만 본다.
 */
@Component
public class SignalMetricSeriesAdapter implements LoadMetricSeriesPort {

    private final QueryMetricSeriesUseCase queryMetricSeries;

    public SignalMetricSeriesAdapter(QueryMetricSeriesUseCase queryMetricSeries) {
        this.queryMetricSeries = queryMetricSeries;
    }

    @Override
    public List<MetricPoint> loadClosedPoints(String metricKey, Instant asOf, int limit) {
        return queryMetricSeries.closedBuckets(metricKey, asOf, limit).stream()
                .map(bucket -> new MetricPoint(bucket.bucketStart(), bucket.failureRate(), bucket.countTotal()))
                .toList();
    }
}
