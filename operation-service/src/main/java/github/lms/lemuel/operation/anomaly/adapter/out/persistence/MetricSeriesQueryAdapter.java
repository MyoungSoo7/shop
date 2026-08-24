package github.lms.lemuel.operation.anomaly.adapter.out.persistence;

import github.lms.lemuel.operation.anomaly.application.port.out.LoadMetricSeriesPort;
import github.lms.lemuel.operation.signal.adapter.out.persistence.MetricBucketJpaEntity;
import github.lms.lemuel.operation.signal.adapter.out.persistence.SpringDataMetricBucketRepository;
import github.lms.lemuel.operation.signal.domain.MetricBucket;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 이상 탐지 입력 시계열 조회 어댑터 — signal BC 가 소유한 {@code ops_metric_bucket} 리포지토리를
 * 읽기 전용으로 재사용한다(같은 테이블의 조회 좌표라 별도 리포지토리를 두지 않는다).
 *
 * <p>리포지토리는 최신 순(desc)으로 반환하므로, 판정 로직이 인덱스로 다루기 쉽게
 * 시간 오름차순으로 뒤집어 도메인 모델로 매핑한다.
 */
@Component
public class MetricSeriesQueryAdapter implements LoadMetricSeriesPort {

    private final SpringDataMetricBucketRepository repository;

    public MetricSeriesQueryAdapter(SpringDataMetricBucketRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<MetricBucket> loadClosedBuckets(String metricKey, Instant before, int limit) {
        List<MetricBucketJpaEntity> desc =
                repository.findRecentClosed(metricKey, before, PageRequest.of(0, limit));
        List<MetricBucket> asc = new ArrayList<>(desc.size());
        for (int i = desc.size() - 1; i >= 0; i--) {
            asc.add(desc.get(i).toDomain());
        }
        return asc;
    }
}
