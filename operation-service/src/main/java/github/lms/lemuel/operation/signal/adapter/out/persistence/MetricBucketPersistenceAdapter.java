package github.lms.lemuel.operation.signal.adapter.out.persistence;

import github.lms.lemuel.operation.signal.application.port.out.UpsertMetricBucketPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class MetricBucketPersistenceAdapter implements UpsertMetricBucketPort {

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
}
