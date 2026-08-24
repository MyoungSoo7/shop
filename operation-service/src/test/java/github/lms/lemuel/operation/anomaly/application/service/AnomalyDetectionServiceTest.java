package github.lms.lemuel.operation.anomaly.application.service;

import github.lms.lemuel.operation.anomaly.application.port.in.DetectAnomaliesUseCase.DetectionSummary;
import github.lms.lemuel.operation.anomaly.application.port.out.LoadMetricSeriesPort;
import github.lms.lemuel.operation.anomaly.application.service.AnomalyIncidentApplier.Outcome;
import github.lms.lemuel.operation.anomaly.domain.AnomalyDecision;
import github.lms.lemuel.operation.anomaly.domain.AnomalyEvaluator;
import github.lms.lemuel.operation.anomaly.domain.RollingWindowBaseline;
import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import github.lms.lemuel.operation.signal.domain.MetricBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnomalyDetectionServiceTest {

    private LoadMetricSeriesPort loadPort;
    private AnomalyIncidentApplier applier;
    private AnomalyDetectionService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadMetricSeriesPort.class);
        applier = mock(AnomalyIncidentApplier.class);

        OpsProperties props = new OpsProperties();
        OpsProperties.Anomaly cfg = props.getAnomaly();
        cfg.setWindowSize(3);
        cfg.setZThreshold(3.0);
        cfg.setCriticalZThreshold(5.0);
        cfg.setMinSampleTotal(30);
        cfg.setFailureRateFloor(0.10);
        cfg.setResolveStreakK(2);
        cfg.getMetricCategory().put("settlement", "SETTLEMENT_FAILURE");

        Clock clock = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC);
        AnomalyEvaluator evaluator = new AnomalyEvaluator(new RollingWindowBaseline());
        service = new AnomalyDetectionService(loadPort, evaluator, applier, props, clock);
    }

    /** total=100, signal = fr*100 인 카운터 버킷. bucketStart 는 판정에 무관하므로 순번만 다르게 둔다. */
    private static MetricBucket bucket(int index, double failureRate) {
        long total = 100;
        long signal = Math.round(failureRate * total);
        return new MetricBucket("settlement", Instant.parse("2026-07-11T00:00:00Z").plusSeconds(index * 300L),
                total, signal, 0, null, 0);
    }

    private static List<MetricBucket> series(double... frs) {
        List<MetricBucket> list = new ArrayList<>();
        for (int i = 0; i < frs.length; i++) {
            list.add(bucket(i, frs[i]));
        }
        return list;
    }

    @Test
    @DisplayName("실패율 급증 → applier 에 ANOMALY 결정 전달, opened 집계")
    void spike_opensIncident() {
        // 베이스라인 3버킷(변동 있는 저실패율) + 급증 1버킷
        when(loadPort.loadClosedBuckets(eq("settlement"), any(), anyInt()))
                .thenReturn(series(0.01, 0.02, 0.03, 0.40));
        when(applier.apply(eq("settlement"), eq(SignalCategory.SETTLEMENT_FAILURE), any(), anyBoolean(), any()))
                .thenReturn(Outcome.OPENED);

        DetectionSummary summary = service.detectOnce();

        ArgumentCaptor<AnomalyDecision> decisionCaptor = ArgumentCaptor.forClass(AnomalyDecision.class);
        ArgumentCaptor<Boolean> resolveCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(applier).apply(eq("settlement"), eq(SignalCategory.SETTLEMENT_FAILURE),
                decisionCaptor.capture(), resolveCaptor.capture(), any());

        assertThat(decisionCaptor.getValue().isAnomaly()).isTrue();
        assertThat(resolveCaptor.getValue()).isFalse();   // 이상일 땐 해제 자격 계산 안 함
        assertThat(summary.opened()).isEqualTo(1);
        assertThat(summary.scanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("히스토리 부족(windowSize+1 미만) → 판정 스킵, applier 미호출")
    void insufficientHistory_isSkipped() {
        when(loadPort.loadClosedBuckets(eq("settlement"), any(), anyInt()))
                .thenReturn(series(0.02, 0.03));   // 2버킷 < windowSize(3)+1

        DetectionSummary summary = service.detectOnce();

        verify(applier, never()).apply(any(), any(), any(), anyBoolean(), any());
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.scanned()).isZero();
    }

    @Test
    @DisplayName("정상 + 직전 K버킷 모두 정상 → applier 에 resolveEligible=true 전달")
    void normalStreak_marksResolveEligible() {
        // 5버킷(=windowSize3+K2) 모두 floor(0.10) 미만 → 전부 NORMAL, 최근 2개 정상 연속
        when(loadPort.loadClosedBuckets(eq("settlement"), any(), anyInt()))
                .thenReturn(series(0.01, 0.02, 0.03, 0.02, 0.02));
        when(applier.apply(eq("settlement"), eq(SignalCategory.SETTLEMENT_FAILURE), any(), anyBoolean(), any()))
                .thenReturn(Outcome.AUTO_RESOLVED);

        DetectionSummary summary = service.detectOnce();

        ArgumentCaptor<AnomalyDecision> decisionCaptor = ArgumentCaptor.forClass(AnomalyDecision.class);
        ArgumentCaptor<Boolean> resolveCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(applier).apply(eq("settlement"), eq(SignalCategory.SETTLEMENT_FAILURE),
                decisionCaptor.capture(), resolveCaptor.capture(), any());

        assertThat(decisionCaptor.getValue().isAnomaly()).isFalse();
        assertThat(resolveCaptor.getValue()).isTrue();
        assertThat(summary.resolved()).isEqualTo(1);
    }
}
