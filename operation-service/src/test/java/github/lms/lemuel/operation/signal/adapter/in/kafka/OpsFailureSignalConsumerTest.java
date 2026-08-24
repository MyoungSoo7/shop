package github.lms.lemuel.operation.signal.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpsFailureSignalConsumerTest {

    @Mock
    RecordSignalUseCase recordSignalUseCase;
    @Mock
    Acknowledgment ack;

    private OpsFailureSignalConsumer consumer() {
        return new OpsFailureSignalConsumer(recordSignalUseCase, new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(String topic, String value, long tsMs) {
        return new ConsumerRecord<>(topic, 0, 0L, tsMs, TimestampType.CREATE_TIME,
                -1, -1, "key", value, new RecordHeaders(), Optional.empty());
    }

    @Test
    void shipping_delayed_는_shipping_분자를_signal_true_로_올리고_envelope_occurredAt_을_쓴다() {
        String envelope = """
                {"category":"SHIPPING_DELAYED","entityId":"42","occurredAt":"2026-07-07T06:03:00Z"}
                """;
        consumer().onShippingDelayed(record("lemuel.ops.shipping.delayed", envelope, 999L), ack);

        verify(recordSignalUseCase).recordEvent("shipping", true,
                Instant.parse("2026-07-07T06:03:00Z"));
        verify(ack).acknowledge();
    }

    @Test
    void occurredAt_이_없으면_record_timestamp_로_폴백한다() {
        long ts = Instant.parse("2026-07-07T06:00:00Z").toEpochMilli();
        consumer().onPaymentFailed(record("lemuel.ops.payment.failed", "{\"entityId\":\"9\"}", ts), ack);

        verify(recordSignalUseCase).recordEvent(DomainEventSignalConsumer.METRIC_PAYMENT, true,
                Instant.ofEpochMilli(ts));
    }

    @Test
    void stock_shipping_은_전용_metricKey_로_signal_true_를_올린다() {
        consumer().onStockDepleted(record("lemuel.ops.stock.depleted", "{}", 1000L), ack);
        consumer().onShippingDelayed(record("lemuel.ops.shipping.delayed", "{}", 1000L), ack);

        verify(recordSignalUseCase).recordEvent(eq("stock"), eq(true), any());
        verify(recordSignalUseCase).recordEvent(eq("shipping"), eq(true), any());
    }

    @Test
    void 회수지연은_재고부족과_다른_metricKey_로_적재된다() {
        // 재고 "부족"과 회수 "지연"은 원인도 조치도 달라, 같은 버킷에 섞이면 대시보드가 무의미해진다.
        consumer().onStockReclaimDelayed(record("lemuel.ops.stock.reclaim_delayed", "{}", 1000L), ack);

        verify(recordSignalUseCase).recordEvent(eq("stock-reclaim"), eq(true), any());
        verify(ack).acknowledge();
    }

    @Test
    void 깨진_JSON_이어도_record_timestamp_폴백으로_적재하고_ack_한다() {
        long ts = 1000L;
        consumer().onOrderFailed(record("lemuel.ops.order.failed", "not json", ts), ack);

        verify(recordSignalUseCase).recordEvent(eq(DomainEventSignalConsumer.METRIC_ORDER), eq(true),
                eq(Instant.ofEpochMilli(ts)));
        verify(ack).acknowledge();
    }
}
