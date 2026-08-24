package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.ClaimOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배치 발행기의 DLQ 트리거 + 상태 전이 검증.
 *
 * <p>핵심 동작: 이벤트가 retryCount >= 10 으로 PENDING → FAILED 전이된 그 시점에 정확히 한 번
 * {@link PublishDlqEventPort#publishToDlq} 가 호출되어야 한다. 한계 미만 실패는 markFailed 만 하고
 * 리스를 해제(releaseClaim)해 다음 주기 재시도를 준비한다.
 */
class OutboxBatchEventPublisherDlqTest {

    private PublishExternalEventPort publishPort;
    private PublishDlqEventPort dlqPort;
    private SaveOutboxEventPort savePort;
    private ClaimOutboxEventPort claimPort;
    private OutboxBatchEventPublisher batchPublisher;

    @BeforeEach
    void setup() {
        publishPort = mock(PublishExternalEventPort.class);
        dlqPort = mock(PublishDlqEventPort.class);
        savePort = mock(SaveOutboxEventPort.class);
        claimPort = mock(ClaimOutboxEventPort.class);
        batchPublisher = new OutboxBatchEventPublisher(publishPort, dlqPort, savePort, claimPort,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("retryCount 가 10 에 도달하는 발행 시도에서 DLQ 로 발행되고 FAILED 로 전이된다")
    void dlqPublishOnRetryLimit() {
        // 9번 실패 누적 (아직 PENDING)
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        for (int i = 0; i < 9; i++) {
            event.markFailed("prior " + i);
        }
        assertThat(event.getStatus().name()).isEqualTo("PENDING");

        // 10번째 시도가 실패 — markFailed 가 FAILED 로 전이시킴
        when(publishPort.publishAsync(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("PG down")));

        batchPublisher.publishBatch(List.of(event));

        // FAILED 전이 시점에 DLQ 발행 정확히 1회, 상태 영속
        verify(dlqPort, times(1)).publishToDlq(event);
        verify(savePort, times(1)).saveAll(List.of(event));
        assertThat(event.isFailed()).isTrue();
    }

    @Test
    @DisplayName("발행 성공하면 DLQ 는 호출되지 않고 PUBLISHED 로 전이된다")
    void noDlqOnSuccess() {
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        when(publishPort.publishAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        batchPublisher.publishBatch(List.of(event));

        verify(dlqPort, never()).publishToDlq(any());
        assertThat(event.getStatus().name()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("retryCount 가 한계 미만이면 markFailed 만 하고 DLQ 없이 리스를 해제한다")
    void noDlqBelowThreshold() {
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        when(publishPort.publishAsync(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("transient")));

        batchPublisher.publishBatch(List.of(event));

        verify(dlqPort, never()).publishToDlq(any());
        // 여전히 PENDING → 재클레임을 위해 리스 해제
        verify(claimPort, times(1)).releaseClaim(any());
        assertThat(event.isFailed()).isFalse();
        assertThat(event.getRetryCount()).isEqualTo(1);
    }
}
