package github.lms.lemuel.operation.signal.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.signal.application.port.in.QueryMetricSeriesUseCase;
import github.lms.lemuel.operation.signal.application.port.out.LoadMetricBucketPort;
import github.lms.lemuel.operation.signal.domain.BucketWindow;
import github.lms.lemuel.operation.signal.domain.MetricBucket;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 신호 시계열 조회 — {@link QueryMetricSeriesUseCase} 구현.
 *
 * <p>하는 일은 두 가지뿐이다: (1) 기준 시각을 버킷 경계로 내림 정렬해 <b>진행 중인 버킷을 잘라내고</b>,
 * (2) 도메인 모델을 포트가 소유한 결과 타입으로 옮긴다. 버킷 폭은 signal 의 설정이므로
 * 호출자에게 묻지 않는다.
 */
@Service
public class MetricSeriesQueryService implements QueryMetricSeriesUseCase {

    private final LoadMetricBucketPort loadMetricBucketPort;
    private final OpsProperties properties;

    public MetricSeriesQueryService(LoadMetricBucketPort loadMetricBucketPort, OpsProperties properties) {
        this.loadMetricBucketPort = loadMetricBucketPort;
        this.properties = properties;
    }

    @Override
    public List<Bucket> closedBuckets(String metricKey, Instant asOf, int limit) {
        int bucketSeconds = properties.getSignal().getBucketSeconds();
        Instant currentBucketStart = BucketWindow.floor(asOf, bucketSeconds);
        return loadMetricBucketPort.findClosedBuckets(metricKey, currentBucketStart, limit).stream()
                .map(MetricSeriesQueryService::toBucket)
                .toList();
    }

    private static Bucket toBucket(MetricBucket bucket) {
        return new Bucket(bucket.bucketStart(), bucket.countTotal(), bucket.countSignal(), bucket.failureRate());
    }
}
