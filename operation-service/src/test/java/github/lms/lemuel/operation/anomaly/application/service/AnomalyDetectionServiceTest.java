package github.lms.lemuel.operation.anomaly.application.service;

import github.lms.lemuel.operation.anomaly.adapter.out.persistence.SpringDataWriteConflictDetector;
import github.lms.lemuel.operation.anomaly.application.port.in.DetectAnomaliesUseCase.DetectionSummary;
import github.lms.lemuel.operation.anomaly.application.port.out.LoadMetricSeriesPort;
import github.lms.lemuel.operation.anomaly.domain.AnomalyEvaluator;
import github.lms.lemuel.operation.anomaly.domain.MetricPoint;
import github.lms.lemuel.operation.anomaly.domain.RollingWindowBaseline;
import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.application.port.in.RaiseAnomalyIncidentUseCase;
import github.lms.lemuel.operation.incident.application.port.in.RaiseAnomalyIncidentUseCase.Command;
import github.lms.lemuel.operation.incident.application.port.in.RaiseAnomalyIncidentUseCase.Result;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스캐너의 관심사는 <b>판정</b>까지다 — 인시던트를 어떻게 여닫을지는 incident 기능의 창구
 * ({@link RaiseAnomalyIncidentUseCase}) 뒤에 있으므로, 여기서는 "무엇을 넘겼는가" 만 고정한다.
 */
class AnomalyDetectionServiceTest {

    private LoadMetricSeriesPort loadPort;
    private RaiseAnomalyIncidentUseCase raiseAnomalyIncident;
    private AnomalyDetectionService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadMetricSeriesPort.class);
        raiseAnomalyIncident = mock(RaiseAnomalyIncidentUseCase.class);

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
        // detector 는 목이 아니라 실제 어댑터 구현 — 진짜 스프링 데이터 예외가 재시도되는지를 증명한다.
        service = new AnomalyDetectionService(loadPort, evaluator, raiseAnomalyIncident, props,
                new SpringDataWriteConflictDetector(), clock);
    }

    /** 표본 100개짜리 한 칸. bucketStart 는 판정에 무관하므로 순번만 다르게 둔다. */
    private static MetricPoint point(int index, double failureRate) {
        return new MetricPoint(Instant.parse("2026-07-11T00:00:00Z").plusSeconds(index * 300L),
                failureRate, 100L);
    }

    private static List<MetricPoint> series(double... frs) {
        List<MetricPoint> list = new ArrayList<>();
        for (int i = 0; i < frs.length; i++) {
            list.add(point(i, frs[i]));
        }
        return list;
    }

    private Command capturedCommand() {
        ArgumentCaptor<Command> captor = ArgumentCaptor.forClass(Command.class);
        verify(raiseAnomalyIncident).apply(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("실패율 급증 → 이상 판정 커맨드 전달, opened 집계")
    void spike_opensIncident() {
        // 베이스라인 3버킷(변동 있는 저실패율) + 급증 1버킷
        when(loadPort.loadClosedPoints(eq("settlement"), any(), anyInt()))
                .thenReturn(series(0.01, 0.02, 0.03, 0.40));
        when(raiseAnomalyIncident.apply(any())).thenReturn(Result.OPENED);

        DetectionSummary summary = service.detectOnce();

        Command command = capturedCommand();
        assertThat(command.metricKey()).isEqualTo("settlement");
        assertThat(command.categoryName()).isEqualTo("SETTLEMENT_FAILURE");
        assertThat(command.anomaly()).isTrue();
        assertThat(command.resolveEligible()).isFalse();   // 이상일 땐 해제 자격 계산 안 함
        assertThat(summary.opened()).isEqualTo(1);
        assertThat(summary.scanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("히스토리 부족(windowSize+1 미만) → 판정 스킵, 인시던트 창구 미호출")
    void insufficientHistory_isSkipped() {
        when(loadPort.loadClosedPoints(eq("settlement"), any(), anyInt()))
                .thenReturn(series(0.02, 0.03));   // 2칸 < windowSize(3)+1

        DetectionSummary summary = service.detectOnce();

        verify(raiseAnomalyIncident, never()).apply(any());
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.scanned()).isZero();
    }

    @Test
    @DisplayName("정상 + 직전 K칸 모두 정상 → resolveEligible=true 로 전달")
    void normalStreak_marksResolveEligible() {
        // 5칸(=windowSize3+K2) 모두 floor(0.10) 미만 → 전부 NORMAL, 최근 2개 정상 연속
        when(loadPort.loadClosedPoints(eq("settlement"), any(), anyInt()))
                .thenReturn(series(0.01, 0.02, 0.03, 0.02, 0.02));
        when(raiseAnomalyIncident.apply(any())).thenReturn(Result.AUTO_RESOLVED);

        DetectionSummary summary = service.detectOnce();

        Command command = capturedCommand();
        assertThat(command.anomaly()).isFalse();
        assertThat(command.resolveEligible()).isTrue();
        assertThat(summary.resolved()).isEqualTo(1);
    }
}
