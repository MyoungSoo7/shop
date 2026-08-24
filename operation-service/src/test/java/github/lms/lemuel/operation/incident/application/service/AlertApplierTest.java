package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.config.OpsProperties;
import github.lms.lemuel.operation.incident.application.port.in.IngestAlertUseCase.AlertCommand;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertApplierTest {

    private static final Instant NOW = Instant.parse("2026-07-06T06:00:00Z");

    @Mock
    LoadIncidentPort loadIncidentPort;
    @Mock
    SaveIncidentPort saveIncidentPort;
    @Mock
    RecordTimelinePort recordTimelinePort;

    private AlertApplier applier;

    @BeforeEach
    void setUp() {
        OpsProperties properties = new OpsProperties();
        properties.setCategoryMapping(Map.of(
                "outbox", "KAFKA_BACKLOG",
                "settlement-batch", "SETTLEMENT_FAILURE",
                "broken", "NOT_A_CATEGORY"));
        applier = new AlertApplier(loadIncidentPort, saveIncidentPort, recordTimelinePort,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AlertCommand firing(String component) {
        return new AlertCommand("fp-1", true,
                Map.of("alertname", "OutboxPendingBacklog", "severity", "warning",
                        "component", component == null ? "" : component),
                Map.of("summary", "Outbox PENDING 적체", "description", "1000건 초과"),
                NOW.minusSeconds(30), null);
    }

    @Test
    void firing_에_활성_인시던트가_없으면_OPEN_인시던트를_만들고_OPENED_타임라인을_남긴다() {
        when(loadIncidentPort.findActive(IncidentSource.ALERTMANAGER, "fp-1")).thenReturn(Optional.empty());
        when(saveIncidentPort.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 42L));

        applier.apply(firing("outbox"));

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(saveIncidentPort).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(saved.getValue().getCategory()).isEqualTo(SignalCategory.KAFKA_BACKLOG);
        assertThat(saved.getValue().getSeverity()).isEqualTo(IncidentSeverity.WARNING);
        assertThat(saved.getValue().getTitle()).isEqualTo("OutboxPendingBacklog");
        assertThat(saved.getValue().getDescription()).isEqualTo("Outbox PENDING 적체\n1000건 초과");
        assertThat(saved.getValue().getFirstSeenAt()).isEqualTo(NOW.minusSeconds(30));

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().incidentId()).isEqualTo(42L);
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.OPENED);
        assertThat(timeline.getValue().actor()).isEqualTo(Incident.AUTO_ACTOR);
    }

    @Test
    void 매핑되지_않은_component_는_UNKNOWN_카테고리로_폴백한다() {
        when(loadIncidentPort.findActive(any(), any())).thenReturn(Optional.empty());
        when(saveIncidentPort.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 1L));

        applier.apply(firing("no-such-component"));

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(saveIncidentPort).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo(SignalCategory.UNKNOWN);
    }

    @Test
    void 매핑값이_유효한_SignalCategory_가_아니어도_UNKNOWN_으로_폴백한다() {
        when(loadIncidentPort.findActive(any(), any())).thenReturn(Optional.empty());
        when(saveIncidentPort.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 1L));

        applier.apply(firing("broken"));

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(saveIncidentPort).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo(SignalCategory.UNKNOWN);
    }

    @Test
    void firing_에_활성_인시던트가_있으면_refire_로_병합하고_최초는_REFIRED_타임라인을_남긴다() {
        Incident active = withId(Incident.openFromAlert("fp-1", SignalCategory.KAFKA_BACKLOG,
                IncidentSeverity.WARNING, "OutboxPendingBacklog", null, "outbox",
                Map.of(), Map.of(), NOW.minusSeconds(600), NOW.minusSeconds(600)), 7L);
        when(loadIncidentPort.findActive(IncidentSource.ALERTMANAGER, "fp-1")).thenReturn(Optional.of(active));
        when(saveIncidentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        applier.apply(firing("outbox"));

        assertThat(active.getOccurrenceCount()).isEqualTo(2);
        assertThat(active.getLastSeenAt()).isEqualTo(NOW);

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.REFIRED);
        assertThat(timeline.getValue().note()).contains("2회");
    }

    @Test
    void 심각도_승격_refire_는_승격_내용을_타임라인_note_에_남긴다() {
        Incident active = withId(Incident.openFromAlert("fp-1", SignalCategory.KAFKA_BACKLOG,
                IncidentSeverity.WARNING, "t", null, "outbox",
                Map.of(), Map.of(), NOW.minusSeconds(600), NOW.minusSeconds(600)), 7L);
        when(loadIncidentPort.findActive(any(), any())).thenReturn(Optional.of(active));
        when(saveIncidentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AlertCommand critical = new AlertCommand("fp-1", true,
                Map.of("severity", "critical", "component", "outbox"), Map.of(), NOW, null);
        applier.apply(critical);

        assertThat(active.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().note()).contains("WARNING").contains("CRITICAL");
    }

    @Test
    void resolved_는_활성_인시던트를_자동_해제하고_AUTO_RESOLVED_타임라인을_남긴다() {
        Incident active = withId(Incident.openFromAlert("fp-1", SignalCategory.KAFKA_BACKLOG,
                IncidentSeverity.WARNING, "t", null, "outbox",
                Map.of(), Map.of(), NOW.minusSeconds(600), NOW.minusSeconds(600)), 7L);
        when(loadIncidentPort.findActive(any(), any())).thenReturn(Optional.of(active));
        when(saveIncidentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant endsAt = NOW.minusSeconds(5);
        applier.apply(new AlertCommand("fp-1", false, Map.of(), Map.of(), NOW.minusSeconds(600), endsAt));

        assertThat(active.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(active.getResolvedBy()).isEqualTo(Incident.AUTO_ACTOR);
        assertThat(active.getResolvedAt()).isEqualTo(endsAt);

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.AUTO_RESOLVED);
    }

    @Test
    void resolved_인데_활성_인시던트가_없으면_no_op_이다() {
        when(loadIncidentPort.findActive(any(), any())).thenReturn(Optional.empty());

        applier.apply(new AlertCommand("fp-x", false, Map.of(), Map.of(), null, NOW));

        verify(saveIncidentPort, never()).save(any());
        verify(recordTimelinePort, never()).record(any());
    }

    /** save 가 id 를 채워 돌려주는 것을 흉내 — 도메인 스냅샷 복사 */
    private static Incident withId(Incident incident, Long id) {
        return Incident.builder()
                .id(id)
                .correlationKey(incident.getCorrelationKey())
                .source(incident.getSource())
                .category(incident.getCategory())
                .severity(incident.getSeverity())
                .status(incident.getStatus())
                .title(incident.getTitle())
                .description(incident.getDescription())
                .service(incident.getService())
                .labels(incident.getLabels())
                .annotations(incident.getAnnotations())
                .firstSeenAt(incident.getFirstSeenAt())
                .lastSeenAt(incident.getLastSeenAt())
                .occurrenceCount(incident.getOccurrenceCount())
                .lastRefireLoggedAt(incident.getLastRefireLoggedAt())
                .acknowledgedAt(incident.getAcknowledgedAt())
                .acknowledgedBy(incident.getAcknowledgedBy())
                .resolvedAt(incident.getResolvedAt())
                .resolvedBy(incident.getResolvedBy())
                .version(incident.getVersion())
                .build();
    }
}
