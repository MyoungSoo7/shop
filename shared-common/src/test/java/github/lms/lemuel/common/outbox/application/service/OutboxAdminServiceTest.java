package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxAdminServiceTest {

    private LoadOutboxEventPort loadPort;
    private SaveOutboxEventPort savePort;
    private OutboxAdminService service;

    @BeforeEach
    void setup() {
        loadPort = mock(LoadOutboxEventPort.class);
        savePort = mock(SaveOutboxEventPort.class);
        service = new OutboxAdminService(loadPort, savePort, new SimpleMeterRegistry());
        when(savePort.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("retry: FAILED 이벤트가 PENDING 으로 복원되고 retryCount 가 0 으로 초기화된다")
    void retry_resets_failed_event() {
        OutboxEvent failed = failedEvent();

        when(loadPort.findByEventId(failed.getEventId())).thenReturn(Optional.of(failed));

        OutboxEvent result = service.retry(failed.getEventId());

        assertThat(result.isPending()).isTrue();
        assertThat(result.getRetryCount()).isZero();
        verify(savePort).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("retry: 존재하지 않는 eventId → IllegalArgumentException")
    void retry_unknown_event() {
        UUID unknown = UUID.randomUUID();
        when(loadPort.findByEventId(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(unknown))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("skip: 사유 + 운영자 ID 가 lastError 에 기록되고 PUBLISHED 로 마킹된다")
    void skip_records_operator_and_reason() {
        OutboxEvent failed = failedEvent();
        when(loadPort.findByEventId(failed.getEventId())).thenReturn(Optional.of(failed));

        OutboxEvent result = service.skip(failed.getEventId(), "운영자 수동 보정 완료", "ops-1");

        assertThat(result.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(result.getLastError()).contains("[SKIPPED]");
        assertThat(result.getLastError()).contains("ops-1");
        assertThat(result.getLastError()).contains("운영자 수동 보정 완료");
    }

    @Test
    @DisplayName("skip: 사유가 비어있으면 IllegalArgumentException (감사 추적 강제)")
    void skip_requires_reason() {
        OutboxEvent failed = failedEvent();
        when(loadPort.findByEventId(failed.getEventId())).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.skip(failed.getEventId(), "", "ops-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listFailed: limit 이 0 또는 100 초과면 기본값 20 으로 보정")
    void listFailed_clamps_limit() {
        service.listFailed(0, 0);
        service.listFailed(0, 999);

        verify(loadPort, times(2)).findFailed(0, 20);
    }

    @Test
    @DisplayName("listFailed: 음수 offset 은 0 으로 보정")
    void listFailed_clamps_offset() {
        service.listFailed(-5, 10);

        verify(loadPort).findFailed(0, 10);
    }

    private static OutboxEvent failedEvent() {
        OutboxEvent e = OutboxEvent.pending("Payment", "42", "PaymentCaptured", "{}");
        for (int i = 0; i < 10; i++) {
            e.markFailed("transient " + i);
        }
        return e;
    }
}
