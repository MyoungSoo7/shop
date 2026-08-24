package github.lms.lemuel.operation.signal.adapter.out.persistence;

import github.lms.lemuel.operation.signal.domain.MetricBucket;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * ops_metric_bucket 매핑 (V4). 적재는 네이티브 UPSERT(리포지토리)로 하고,
 * 이 엔티티는 주로 조회(Phase 3 베이스라인)·테스트 검증용 읽기 매핑이다.
 */
@Entity
@Table(name = "ops_metric_bucket")
public class MetricBucketJpaEntity {

    @EmbeddedId
    private MetricBucketId id;

    @Column(name = "count_total", nullable = false)
    private long countTotal;

    @Column(name = "count_signal", nullable = false)
    private long countSignal;

    @Column(name = "value_sum", nullable = false)
    private double valueSum;

    @Column(name = "value_max")
    private Double valueMax;

    @Column(name = "sample_count", nullable = false)
    private long sampleCount;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected MetricBucketJpaEntity() {
    }

    public MetricBucket toDomain() {
        return new MetricBucket(id.getMetricKey(), id.getBucketStart(),
                countTotal, countSignal, valueSum, valueMax, sampleCount);
    }

    public MetricBucketId getId() {
        return id;
    }

    public long getCountTotal() {
        return countTotal;
    }

    public long getCountSignal() {
        return countSignal;
    }

    public double getValueSum() {
        return valueSum;
    }

    public Double getValueMax() {
        return valueMax;
    }

    public long getSampleCount() {
        return sampleCount;
    }
}
