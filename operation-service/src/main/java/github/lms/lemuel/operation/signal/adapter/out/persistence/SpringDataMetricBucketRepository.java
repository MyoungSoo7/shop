package github.lms.lemuel.operation.signal.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * ops_metric_bucket UPSERT 리포지토리.
 *
 * <p>네이티브 쿼리는 hibernate default_schema(opslab) 의 적용을 받지 않으므로 스키마를 직접 명시한다
 * (물리 DB 는 lemuel_operation, 스키마는 opslab — shared-common Outbox 네이티브 쿼리와 동일 관례).
 * ON CONFLICT DO UPDATE 로 동시 다중 컨슈머/폴러가 같은 버킷에 몰려도 원자적으로 누적된다.
 */
public interface SpringDataMetricBucketRepository extends JpaRepository<MetricBucketJpaEntity, MetricBucketId> {

    /** 카운터 이벤트 누적: count_total += 1, count_signal += :signalDelta(0|1). */
    @Modifying
    @Query(value = """
            INSERT INTO opslab.ops_metric_bucket
                (metric_key, bucket_start, count_total, count_signal, value_sum, sample_count, updated_at)
            VALUES (:metricKey, :bucketStart, 1, :signalDelta, 0, 0, NOW())
            ON CONFLICT (metric_key, bucket_start) DO UPDATE
            SET count_total  = opslab.ops_metric_bucket.count_total  + 1,
                count_signal = opslab.ops_metric_bucket.count_signal + :signalDelta,
                updated_at   = NOW()
            """, nativeQuery = true)
    void upsertEvent(@Param("metricKey") String metricKey,
                     @Param("bucketStart") Instant bucketStart,
                     @Param("signalDelta") long signalDelta);

    /** 게이지 표본 누적: value_sum += :value, value_max = max(..), sample_count += 1. */
    @Modifying
    @Query(value = """
            INSERT INTO opslab.ops_metric_bucket
                (metric_key, bucket_start, count_total, count_signal, value_sum, value_max, sample_count, updated_at)
            VALUES (:metricKey, :bucketStart, 0, 0, :value, :value, 1, NOW())
            ON CONFLICT (metric_key, bucket_start) DO UPDATE
            SET value_sum    = opslab.ops_metric_bucket.value_sum + :value,
                value_max    = GREATEST(opslab.ops_metric_bucket.value_max, :value),
                sample_count = opslab.ops_metric_bucket.sample_count + 1,
                updated_at   = NOW()
            """, nativeQuery = true)
    void upsertGauge(@Param("metricKey") String metricKey,
                     @Param("bucketStart") Instant bucketStart,
                     @Param("value") double value);

    /**
     * Phase 3 이상 탐지 입력 — 지정 metric_key 의 마감된(bucket_start &lt; :before) 버킷을
     * 최신 순으로 최대 Pageable.pageSize 개 조회. idx_metric_bucket_recent(metric_key, bucket_start DESC) 활용.
     */
    @Query("""
            select e from MetricBucketJpaEntity e
            where e.id.metricKey = :metricKey and e.id.bucketStart < :before
            order by e.id.bucketStart desc
            """)
    List<MetricBucketJpaEntity> findRecentClosed(@Param("metricKey") String metricKey,
                                                 @Param("before") Instant before,
                                                 Pageable pageable);
}
