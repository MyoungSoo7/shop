package github.lms.lemuel.operation.signal.adapter.in.kafka;

import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DomainEventSignalConsumerTest {

    @Mock
    RecordSignalUseCase recordSignalUseCase;
    @Mock
    Acknowledgment ack;

    private ConsumerRecord<String, String> record(String topic, long timestampMs) {
        ConsumerRecord<String, String> r =
                new ConsumerRecord<>(topic, 0, 0L, timestampMs, org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                        -1, -1, "key", "{}", new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        return r;
    }

    @Test
    void order_created_는_order_분모를_record_timestamp_로_올리고_ack_한다() {
        long ts = Instant.parse("2026-07-07T06:03:00Z").toEpochMilli();
        new DomainEventSignalConsumer(recordSignalUseCase)
                .onOrderCreated(record("lemuel.order.created", ts), ack);

        verify(recordSignalUseCase).recordEvent(DomainEventSignalConsumer.METRIC_ORDER, false,
                Instant.ofEpochMilli(ts));
        verify(ack).acknowledge();
    }

    @Test
    void payment_captured_는_payment_분모를_올린다() {
        long ts = Instant.parse("2026-07-07T06:03:00Z").toEpochMilli();
        new DomainEventSignalConsumer(recordSignalUseCase)
                .onPaymentCaptured(record("lemuel.payment.captured", ts), ack);

        verify(recordSignalUseCase).recordEvent(eq(DomainEventSignalConsumer.METRIC_PAYMENT), eq(false), any());
        verify(ack).acknowledge();
    }

    @Test
    void 적재_실패해도_ack_해서_컨슈머를_막지_않는다() {
        doThrow(new RuntimeException("db down")).when(recordSignalUseCase).recordEvent(any(), anyBoolean(), any());

        new DomainEventSignalConsumer(recordSignalUseCase)
                .onOrderCreated(record("lemuel.order.created", 1_000L), ack);

        verify(ack).acknowledge();
    }
}
