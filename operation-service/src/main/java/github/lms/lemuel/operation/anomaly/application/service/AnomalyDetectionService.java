package github.lms.lemuel.operation.anomaly.application.service;

import github.lms.lemuel.operation.anomaly.application.port.in.DetectAnomaliesUseCase;
import github.lms.lemuel.operation.anomaly.application.port.out.LoadMetricSeriesPort;
import github.lms.lemuel.operation.anomaly.application.service.AnomalyIncidentApplier.Outcome;
import github.lms.lemuel.operation.anomaly.domain.AnomalyDecision;
import github.lms.lemuel.operation.anomaly.domain.AnomalyEvaluator;
import github.lms.lemuel.operation.anomaly.domain.AnomalyThreshold;
import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import github.lms.lemuel.operation.signal.domain.BucketWindow;
import github.lms.lemuel.operation.signal.domain.MetricBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 이상 탐지 오케스트레이터 — 트랜잭션 없이 metric 을 순회하고, metric 1건의 반영은
 * {@link AnomalyIncidentApplier} 의 독립 트랜잭션에 위임한다({@code IngestAlertService} 와 동일 구조).
 *
 * <p>판정 흐름(metric 당):
 * <ol>
 *   <li>마감된 버킷을 {@code windowSize + resolveStreakK} 개까지 시간순으로 읽는다.</li>
 *   <li>히스토리가 {@code windowSize + 1} 미만이면 스킵(콜드스타트).</li>
 *   <li>가장 최근(직전 마감) 버킷을 롤링 베이스라인 대비 판정한다.</li>
 *   <li>이상이면 인시던트 생성/refire, 정상이면서 직전 K개가 모두 정상이면 자동 해제한다.</li>
 * </ol>
 *
 * <p>한 metric 의 실패는 다른 metric 을 막지 않는다(건별 try). 동시 경쟁(중복 INSERT/낙관적 락)은
 * 새 트랜잭션 재시도로 refire 수렴 — 스캐너는 단일 스레드라 실제 경쟁은 드물지만 견고성을 유지한다.
 */
@Service
public class AnomalyDetectionService implements DetectAnomaliesUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    static final int MAX_ATTEMPTS = 5;

    private final LoadMetricSeriesPort loadMetricSeriesPort;
    private final AnomalyEvaluator evaluator;
    private final AnomalyIncidentApplier applier;
    private final OpsProperties properties;
    private final Clock clock;

    public AnomalyDetectionService(LoadMetricSeriesPort loadMetricSeriesPort, AnomalyEvaluator evaluator,
                                   AnomalyIncidentApplier applier, OpsProperties properties, Clock clock) {
        this.loadMetricSeriesPort = loadMetricSeriesPort;
        this.evaluator = evaluator;
        this.applier = applier;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public DetectionSummary detectOnce() {
        OpsProperties.Anomaly cfg = properties.getAnomaly();
        AnomalyThreshold threshold = new AnomalyThreshold(
                cfg.getZThreshold(), cfg.getCriticalZThreshold(), cfg.getWindowSize(),
                cfg.getMinSampleTotal(), cfg.getFailureRateFloor(), cfg.getResolveStreakK());

        int bucketSeconds = properties.getSignal().getBucketSeconds();
        Instant now = Instant.now(clock);
        // 현재 진행 중인(미마감) 버킷은 부분 집계라 판정에서 제외 — 직전에 "마감된" 버킷까지만 본다.
        Instant currentFloor = BucketWindow.floor(now, bucketSeconds);
        int limit = threshold.windowSize() + threshold.resolveStreakK();

        int scanned = 0, opened = 0, refired = 0, resolved = 0, skipped = 0;

        for (Map.Entry<String, String> entry : cfg.getMetricCategory().entrySet()) {
            String metricKey = entry.getKey();
            SignalCategory category = resolveCategory(metricKey, entry.getValue());
            try {
                Optional<Outcome> result = scanMetric(metricKey, category, threshold, currentFloor, now, limit);
                if (result.isEmpty()) {
                    skipped++;
                    continue;
                }
                scanned++;
                switch (result.get()) {
                    case OPENED -> opened++;
                    case REFIRED -> refired++;
                    case AUTO_RESOLVED -> resolved++;
                    case NONE -> { /* 정상 유지 — 집계 대상 아님 */ }
                }
            } catch (Exception e) {
                log.error("이상 탐지 metric 처리 실패 — 다른 metric 계속: metric={}", metricKey, e);
            }
        }

        if (opened + refired + resolved > 0) {
            log.info("이상 탐지 스캔 완료: scanned={} opened={} refired={} resolved={} skipped={}",
                    scanned, opened, refired, resolved, skipped);
        }
        return new DetectionSummary(scanned, opened, refired, resolved, skipped);
    }

    private Optional<Outcome> scanMetric(String metricKey, SignalCategory category, AnomalyThreshold t,
                                         Instant currentFloor, Instant now, int limit) {
        List<MetricBucket> series = loadMetricSeriesPort.loadClosedBuckets(metricKey, currentFloor, limit);
        int m = series.size();
        int w = t.windowSize();
        if (m < w + 1) {
            log.debug("히스토리 부족 — 판정 스킵: metric={} buckets={} (필요 {})", metricKey, m, w + 1);
            return Optional.empty();
        }

        AnomalyDecision current = evaluateAt(series, m - 1, w, t);
        boolean resolveEligible = !current.isAnomaly() && isNormalStreak(series, w, t, t.resolveStreakK());

        return Optional.of(applyWithConflictRetry(metricKey, category, current, resolveEligible, now));
    }

    /** series[index] 를 그 직전 windowSize 개 버킷의 failure_rate 베이스라인으로 판정. index >= windowSize 전제. */
    private AnomalyDecision evaluateAt(List<MetricBucket> series, int index, int windowSize, AnomalyThreshold t) {
        MetricBucket target = series.get(index);
        double[] window = new double[windowSize];
        for (int k = 0; k < windowSize; k++) {
            window[k] = series.get(index - windowSize + k).failureRate();
        }
        return evaluator.evaluate(target.failureRate(), target.countTotal(), window, t);
    }

    /** 직전 K개 버킷이 모두 정상(게이트 미충족)이어야 자동 해제 자격 — 각 버킷은 자기 직전 윈도우로 재판정. */
    private boolean isNormalStreak(List<MetricBucket> series, int windowSize, AnomalyThreshold t, int k) {
        int m = series.size();
        if (m < windowSize + k) {
            return false; // K개 연속을 증명할 히스토리가 없음 — 보수적으로 유지(해제 안 함)
        }
        for (int s = 0; s < k; s++) {
            if (evaluateAt(series, m - 1 - s, windowSize, t).isAnomaly()) {
                return false;
            }
        }
        return true;
    }

    private Outcome applyWithConflictRetry(String metricKey, SignalCategory category,
                                           AnomalyDecision decision, boolean resolveEligible, Instant now) {
        for (int attempt = 1; ; attempt++) {
            try {
                return applier.apply(metricKey, category, decision, resolveEligible, now);
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("이상 인시던트 반영 경쟁 감지 — 재시도 {}/{}: metric={}", attempt, MAX_ATTEMPTS, metricKey);
            }
        }
    }

    private SignalCategory resolveCategory(String metricKey, String categoryName) {
        try {
            return SignalCategory.valueOf(categoryName);
        } catch (IllegalArgumentException e) {
            log.warn("anomaly metric-category 값이 SignalCategory 가 아님: metric={} value={} — UNKNOWN 폴백",
                    metricKey, categoryName);
            return SignalCategory.UNKNOWN;
        }
    }
}
