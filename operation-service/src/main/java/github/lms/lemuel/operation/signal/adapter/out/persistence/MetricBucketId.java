package github.lms.lemuel.operation.signal.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** ops_metric_bucket 복합 PK (metric_key, bucket_start). */
@Embeddable
public class MetricBucketId implements Serializable {

    @Column(name = "metric_key", nullable = false, length = 100)
    private String metricKey;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    protected MetricBucketId() {
    }

    public MetricBucketId(String metricKey, Instant bucketStart) {
        this.metricKey = metricKey;
        this.bucketStart = bucketStart;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public Instant getBucketStart() {
        return bucketStart;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MetricBucketId that)) return false;
        return Objects.equals(metricKey, that.metricKey) && Objects.equals(bucketStart, that.bucketStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metricKey, bucketStart);
    }
}
