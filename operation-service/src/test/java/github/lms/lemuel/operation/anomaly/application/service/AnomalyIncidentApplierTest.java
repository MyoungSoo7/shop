package github.lms.lemuel.operation.anomaly.application.service;

import github.lms.lemuel.operation.anomaly.domain.AnomalyDecision;
import github.lms.lemuel.operation.anomaly.domain.AnomalyVerdict;
import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.application.port.out.LoadIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.RecordTimelinePort;
import github.lms.lemuel.operation.incident.application.port.out.SaveIncidentPort;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentSource;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import github.lms.lemuel.operation.incident.domain.SignalCategory;
import github.lms.lemuel.operation.incident.domain.TimelineEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 이상 판정 1건을 인시던트 라이프사이클에 반영하는 경계.
 *
 * <p>여기서 지키려는 것은 두 가지다. metric 당 활성 인시던트는 하나여야 하고(같은 이상이
 * 매 스캔마다 새 인시던트를 낳으면 온콜은 같은 장애를 수십 번 받는다), 정상 복귀는 <b>K회 연속
 * 지속됐을 때만</b> 자동 해제여야 한다(한 번 튄 값으로 닫으면 장애가 열린 채 조용해진다).
 *
 * <p>Alertmanager 경로와 키 공간이 분리되어 있다는 것도 함께 고정한다 —
 * {@code source=ANOMALY} 로 조회하지 않으면 웹훅이 연 인시던트를 이상 탐지가 갱신해 버린다.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyIncidentApplierTest {

    private static final Instant NOW = Instant.parse("2026-08-22T06:00:00Z");
    private static final String METRIC = "settlement";

    @Mock
    LoadIncidentPort loadIncidentPort;
    @Mock
    SaveIncidentPort saveIncidentPort;
    @Mock
    RecordTimelinePort recordTimelinePort;

    private AnomalyIncidentApplier applier;

    @BeforeEach
    void setUp() {
        OpsProperties properties = new OpsProperties();
        properties.setRefireTimelineSuppression(Duration.ofMinutes(10));
        applier = new AnomalyIncidentApplier(loadIncidentPort, saveIncidentPort,
                recordTimelinePort, properties);
    }

    private static AnomalyDecision anomaly(boolean critical) {
        return new AnomalyDecision(AnomalyVerdict.ANOMALY, 4.2, 0.01, 0.005,
                0.12, 500L, critical, "z=4.20 (평균 0.010, 표준편차 0.005)");
    }

    private static AnomalyDecision normal() {
        return new AnomalyDecision(AnomalyVerdict.NORMAL, 0.3, 0.01, 0.005,
                0.011, 500L, false, "정상 범위");
    }

    private static Incident activeIncident(IncidentSeverity severity) {
        return Incident.openFromAnomaly(METRIC, SignalCategory.SETTLEMENT_FAILURE, severity,
                "실패율 이상 급증: " + METRIC, "이전 판정", NOW.minusSeconds(600));
    }

    private void stubSaveReturnsArgument() {
        when(saveIncidentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("활성 인시던트가 없는 이상 판정 → 새로 연다 (source=ANOMALY, correlation=metric_key)")
    void opensNewIncident() {
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        AnomalyIncidentApplier.Outcome outcome =
                applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, anomaly(false), false, NOW);

        assertThat(outcome).isEqualTo(AnomalyIncidentApplier.Outcome.OPENED);

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(saveIncidentPort).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(IncidentSource.ANOMALY);
        assertThat(saved.getValue().getCorrelationKey()).isEqualTo(METRIC);
        assertThat(saved.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(saved.getValue().getSeverity()).isEqualTo(IncidentSeverity.WARNING);

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.OPENED);
        // 왜 열렸는지가 타임라인에 남아야 한다 — z 값 없이 "이상"만 남으면 조사할 수 없다.
        assertThat(timeline.getValue().note()).contains("z=4.20");
    }

    @Test
    @DisplayName("critical 힌트가 붙으면 CRITICAL 로 연다")
    void opensCritical() {
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, anomaly(true), false, NOW);

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(saveIncidentPort).save(saved.capture());
        assertThat(saved.getValue().getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    @DisplayName("이미 활성 인시던트가 있으면 새로 열지 않고 refire — 발생 횟수만 누적")
    void refiresInsteadOfOpeningDuplicate() {
        Incident active = activeIncident(IncidentSeverity.WARNING);
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.of(active));
        stubSaveReturnsArgument();

        AnomalyIncidentApplier.Outcome outcome =
                applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, anomaly(false), false, NOW);

        assertThat(outcome).isEqualTo(AnomalyIncidentApplier.Outcome.REFIRED);
        assertThat(active.getOccurrenceCount()).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    @DisplayName("refire 중 severity 가 올라가면 승격 사실이 타임라인에 남는다")
    void refireLogsSeverityUpgrade() {
        Incident active = activeIncident(IncidentSeverity.WARNING);
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.of(active));
        stubSaveReturnsArgument();

        applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, anomaly(true), false, NOW);

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.REFIRED);
        assertThat(timeline.getValue().note()).contains("승격").contains("WARNING").contains("CRITICAL");
        assertThat(active.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    @DisplayName("억제 간격 안의 반복 refire 는 타임라인을 더럽히지 않는다")
    void refireWithinSuppressionWindowSkipsTimeline() {
        Incident active = activeIncident(IncidentSeverity.WARNING);
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.of(active));
        stubSaveReturnsArgument();

        // 1회차: 타임라인 기록, 2회차: 10분 억제 창 안이라 기록하지 않는다.
        applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, anomaly(false), false, NOW);
        applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, anomaly(false), false, NOW.plusSeconds(60));

        verify(recordTimelinePort).record(any());
        assertThat(active.getOccurrenceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("정상 복귀가 지속되면 자동 해제하고 사유를 남긴다")
    void autoResolvesWhenRecoverySustained() {
        Incident active = activeIncident(IncidentSeverity.CRITICAL);
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.of(active));
        stubSaveReturnsArgument();

        AnomalyIncidentApplier.Outcome outcome =
                applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, normal(), true, NOW);

        assertThat(outcome).isEqualTo(AnomalyIncidentApplier.Outcome.AUTO_RESOLVED);
        assertThat(active.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(active.getResolvedBy()).isEqualTo(Incident.ANOMALY_ACTOR);

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.AUTO_RESOLVED);
    }

    @Test
    @DisplayName("정상이지만 복귀가 아직 지속되지 않았으면 아무것도 하지 않는다")
    void doesNothingWhenRecoveryNotYetSustained() {
        Incident active = activeIncident(IncidentSeverity.WARNING);
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.of(active));

        AnomalyIncidentApplier.Outcome outcome =
                applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, normal(), false, NOW);

        // 한 번 튄 정상값으로 닫으면 장애가 열린 채 조용해진다.
        assertThat(outcome).isEqualTo(AnomalyIncidentApplier.Outcome.NONE);
        assertThat(active.getStatus()).isEqualTo(IncidentStatus.OPEN);
        verify(saveIncidentPort, never()).save(any());
        verifyNoInteractions(recordTimelinePort);
    }

    @Test
    @DisplayName("정상이고 활성 인시던트도 없으면 아무것도 하지 않는다")
    void doesNothingWhenNothingIsWrong() {
        when(loadIncidentPort.findActive(IncidentSource.ANOMALY, METRIC)).thenReturn(Optional.empty());

        AnomalyIncidentApplier.Outcome outcome =
                applier.apply(METRIC, SignalCategory.SETTLEMENT_FAILURE, normal(), true, NOW);

        assertThat(outcome).isEqualTo(AnomalyIncidentApplier.Outcome.NONE);
        verify(saveIncidentPort, never()).save(any());
        verifyNoInteractions(recordTimelinePort);
    }
}
