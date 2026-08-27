package github.lms.lemuel.operation.signal.adapter.out.persistence;

import github.lms.lemuel.operation.signal.application.port.out.LoadMetricBucketPort;
import github.lms.lemuel.operation.signal.application.port.out.UpsertMetricBucketPort;
import github.lms.lemuel.operation.signal.domain.MetricBucket;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class MetricBucketPersistenceAdapter implements UpsertMetricBucketPort, LoadMetricBucketPort {

    private final SpringDataMetricBucketRepository repository;

    public MetricBucketPersistenceAdapter(SpringDataMetricBucketRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void incrementEvent(String metricKey, Instant bucketStart, boolean signal) {
        repository.upsertEvent(metricKey, bucketStart, signal ? 1L : 0L);
    }

    @Override
    @Transactional
    public void accumulateGauge(String metricKey, Instant bucketStart, double value) {
        repository.upsertGauge(metricKey, bucketStart, value);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricBucket> findClosedBuckets(String metricKey, Instant before, int limit) {
        // idx_metric_bucket_recent 는 (metric_key, bucket_start DESC) 라 역순 조회가 인덱스를 탄다.
        // 포트 계약은 오름차순이므로 여기서 뒤집는다 — 정렬 비용은 limit 개(수십 건) 수준이다.
        List<MetricBucketJpaEntity> descending =
                repository.findRecentClosed(metricKey, before, PageRequest.of(0, limit));
        List<MetricBucket> ascending = new ArrayList<>(descending.size());
        for (int i = descending.size() - 1; i >= 0; i--) {
            ascending.add(descending.get(i).toDomain());
        }
        return ascending;
    }
}
