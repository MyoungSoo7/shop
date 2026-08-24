package github.lms.lemuel.common.outbox;

import github.lms.lemuel.common.outbox.adapter.out.event.KafkaOutboxPublisher;
import github.lms.lemuel.common.outbox.application.port.out.ClaimOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.OutboxBatchEventPublisher;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KafkaOutboxPublisher 동기/비동기·실패 경로와 OutboxBatchEventPublisher 의 경계 케이스
 * (빈 배치·동기 dispatch 예외·DLQ 발행 실패 삼킴)를 커버한다.
 */
class PublisherEdgeCasesTest {

    private static OutboxEvent event() {
        return OutboxEvent.pending("Payment", "7", "PaymentCaptured", "{}", "00-t-s-01");
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> sendResult() {
        RecordMetadata md = new RecordMetadata(new TopicPartition("t", 0), 0L, 0, 0L, 0, 0);
        return new SendResult<>(null, md);
    }

    @Test
    @DisplayName("publishAsync: send future 를 그대로 반환하며 성공 시 완료")
    @SuppressWarnings("unchecked")
    void publishAsyncSuccess() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(template);

        CompletableFuture<Void> future = publisher.publishAsync(event());
        assertThat(future).isCompleted();
    }

    @Test
    @DisplayName("publish(동기): send 실패 future → RuntimeException 으로 래핑")
    @SuppressWarnings("unchecked")
    void publishSyncWrapsFailure() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(template);

        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka publish failed");
    }

    @Test
    @DisplayName("배치: 빈 목록이면 PublishOutcome(0,0), 포트 호출 없음")
    void batchEmpty() {
        PublishExternalEventPort publish = mock(PublishExternalEventPort.class);
        SaveOutboxEventPort save = mock(SaveOutboxEventPort.class);
        OutboxBatchEventPublisher batch = new OutboxBatchEventPublisher(
                publish, mock(PublishDlqEventPort.class), save, mock(ClaimOutboxEventPort.class),
                new SimpleMeterRegistry());

        OutboxBatchEventPublisher.PublishOutcome outcome = batch.publishBatch(List.of());

        assertThat(outcome.published()).isZero();
        assertThat(outcome.failed()).isZero();
        verify(save, never()).saveAll(any());
    }

    @Test
    @DisplayName("배치: publishAsync 가 동기적으로 던져도 실패로 집계, DLQ 발행 실패는 삼킨다")
    void batchSyncThrowAndDlqFailureSwallowed() {
        PublishExternalEventPort publish = mock(PublishExternalEventPort.class);
        PublishDlqEventPort dlq = mock(PublishDlqEventPort.class);
        SaveOutboxEventPort save = mock(SaveOutboxEventPort.class);
        ClaimOutboxEventPort claim = mock(ClaimOutboxEventPort.class);
        OutboxBatchEventPublisher batch = new OutboxBatchEventPublisher(publish, dlq, save, claim,
                new SimpleMeterRegistry());

        // 9회 사전 실패로 임계 직전 → 이번 실패에서 FAILED 전이 → DLQ 시도
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        for (int i = 0; i < 9; i++) event.markFailed("prior");
        // publishAsync 가 동기적으로 예외를 던지는 경로(try/catch → failedFuture)
        when(publish.publishAsync(any())).thenThrow(new RuntimeException("sync boom"));
        // DLQ 발행 자체도 실패 → publishToDlqQuietly 가 삼켜야 함
        doThrow(new RuntimeException("dlq down")).when(dlq).publishToDlq(any());

        OutboxBatchEventPublisher.PublishOutcome outcome = batch.publishBatch(List.of(event));

        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(event.isFailed()).isTrue();
        verify(dlq, times(1)).publishToDlq(event);
        verify(save, times(1)).saveAll(List.of(event));
    }
}
