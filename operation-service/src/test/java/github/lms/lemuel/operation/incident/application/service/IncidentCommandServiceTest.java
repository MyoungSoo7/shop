package github.lms.lemuel.operation.incident.application.service;

import github.lms.lemuel.operation.incident.application.port.in.IncidentNotFoundException;
import github.lms.lemuel.operation.incident.application.port.out.LoadIncidentPort;
import github.lms.lemuel.operation.incident.application.port.out.RecordTimelinePort;
import github.lms.lemuel.operation.incident.application.port.out.SaveIncidentPort;
import github.lms.lemuel.operation.incident.domain.Incident;
import github.lms.lemuel.operation.incident.domain.IncidentSeverity;
import github.lms.lemuel.operation.incident.domain.IncidentStatus;
import github.lms.lemuel.operation.incident.domain.IncidentTimelineEntry;
import github.lms.lemuel.operation.incident.domain.InvalidIncidentTransitionException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-06T06:00:00Z");

    @Mock
    LoadIncidentPort loadIncidentPort;
    @Mock
    SaveIncidentPort saveIncidentPort;
    @Mock
    RecordTimelinePort recordTimelinePort;

    private IncidentCommandService service;

    @BeforeEach
    void setUp() {
        service = new IncidentCommandService(loadIncidentPort, saveIncidentPort, recordTimelinePort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Incident openIncident() {
        return Incident.builder()
                .id(10L)
                .correlationKey("fp-1")
                .source(github.lms.lemuel.operation.incident.domain.IncidentSource.ALERTMANAGER)
                .category(SignalCategory.SETTLEMENT_FAILURE)
                .severity(IncidentSeverity.CRITICAL)
                .status(IncidentStatus.OPEN)
                .title("SettlementBatchFailure")
                .labels(Map.of())
                .annotations(Map.of())
                .firstSeenAt(NOW.minusSeconds(300))
                .lastSeenAt(NOW.minusSeconds(60))
                .build();
    }

    @Test
    void ack_는_상태를_전이하고_ACKNOWLEDGED_타임라인을_남긴다() {
        Incident incident = openIncident();
        when(loadIncidentPort.findById(10L)).thenReturn(Optional.of(incident));
        when(saveIncidentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Incident result = service.acknowledge(10L, "admin@lemuel", "폴러 재시작 중");

        assertThat(result.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(result.getAcknowledgedBy()).isEqualTo("admin@lemuel");

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.ACKNOWLEDGED);
        assertThat(timeline.getValue().actor()).isEqualTo("admin@lemuel");
        assertThat(timeline.getValue().note()).isEqualTo("폴러 재시작 중");
    }

    @Test
    void resolve_와_false_positive_는_각_타임라인_이벤트를_남긴다() {
        Incident incident = openIncident();
        when(loadIncidentPort.findById(10L)).thenReturn(Optional.of(incident));
        when(saveIncidentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Incident resolved = service.resolve(10L, "admin", null);
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);

        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.RESOLVED);
    }

    @Test
    void 터미널_상태_재전이는_도메인_예외가_그대로_전파된다() {
        Incident incident = openIncident();
        incident.resolve("admin", NOW);
        when(loadIncidentPort.findById(10L)).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> service.acknowledge(10L, "admin", null))
                .isInstanceOf(InvalidIncidentTransitionException.class);
    }

    @Test
    void 없는_인시던트는_IncidentNotFoundException() {
        when(loadIncidentPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(99L, "admin", null))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    @Test
    void comment_는_상태_전이_없이_COMMENT_타임라인만_남긴다() {
        Incident incident = openIncident();
        when(loadIncidentPort.findById(10L)).thenReturn(Optional.of(incident));
        when(recordTimelinePort.record(any())).thenAnswer(inv -> inv.getArgument(0));

        service.comment(10L, "admin", "PG사 문의 접수함");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        ArgumentCaptor<IncidentTimelineEntry> timeline = ArgumentCaptor.forClass(IncidentTimelineEntry.class);
        verify(recordTimelinePort).record(timeline.capture());
        assertThat(timeline.getValue().eventType()).isEqualTo(TimelineEventType.COMMENT);
        assertThat(timeline.getValue().note()).isEqualTo("PG사 문의 접수함");
    }
}
