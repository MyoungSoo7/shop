package github.lms.lemuel.user.adapter.out.event;

import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * user 도메인 이벤트 Outbox 어댑터 단위 테스트 — 페이로드 직렬화 + OutboxEvent 적재 검증.
 */
@ExtendWith(MockitoExtension.class)
class OutboxBackedUserEventPublisherTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock TraceContextCapture traceContextCapture;

    private OutboxBackedUserEventPublisher publisher() {
        return new OutboxBackedUserEventPublisher(saveOutboxEventPort, OutboxJson.mapper(), traceContextCapture);
    }

    @Test
    @DisplayName("publishMembershipChanged: UserMembershipChanged 이벤트를 Outbox 에 적재")
    void publishMembershipChanged() {
        when(traceContextCapture.captureCurrentTraceParent()).thenReturn("trace-1");

        publisher().publishMembershipChanged(7L, "COMPANY", "APPROVED", true);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(saveOutboxEventPort).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("User");
        assertThat(event.getAggregateId()).isEqualTo("7");
        assertThat(event.getEventType()).isEqualTo("UserMembershipChanged");
        assertThat(event.getPayload()).contains("\"membershipStatus\":\"APPROVED\"");
        assertThat(event.getTraceParent()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("publishUserRegistered: UserRegistered 이벤트를 Outbox 에 적재")
    void publishUserRegistered() {
        when(traceContextCapture.captureCurrentTraceParent()).thenReturn(null);

        publisher().publishUserRegistered(9L, "new@b.com");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(saveOutboxEventPort).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("UserRegistered");
        assertThat(event.getPayload()).contains("\"email\":\"new@b.com\"");
    }
}
