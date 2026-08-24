package github.lms.lemuel.operation.signal.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.signal.application.port.in.RecordSignalUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 실패 신호 이벤트 → 신호 버킷 <b>분자</b>(count_signal) 적재 (Phase 2b 채널 A 분자).
 *
 * <p>각 서비스가 실패 지점에서 best-effort 로 발행한 {@code lemuel.ops.*.failed} 를 operation 전용
 * 그룹으로 구독해 {@code recordEvent(metricKey, signal=true, occurredAt)} 로 올린다 — 이때
 * count_total 도 함께 +1 되어(시도) failure_rate = signal/total 이 성립한다(성공 분모는 Phase 2a).
 *
 * <p>버킷 시각은 envelope 의 {@code occurredAt} 을 우선 쓰고, 없으면 record timestamp 로 폴백한다.
 * Phase 2a 성공 컨슈머와 동일하게 멱등 미적용(통계 5분 버킷) + 적재 실패해도 ack(컨슈머 정지 방지).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OpsFailureSignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpsFailureSignalConsumer.class);
    private static final String GROUP = "lemuel-operation";

    private final RecordSignalUseCase recordSignalUseCase;
    private final ObjectMapper objectMapper;

    public OpsFailureSignalConsumer(RecordSignalUseCase recordSignalUseCase, ObjectMapper objectMapper) {
        this.recordSignalUseCase = recordSignalUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "lemuel.ops.order.failed", groupId = GROUP)
    public void onOrderFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        recordFailure(DomainEventSignalConsumer.METRIC_ORDER, record, ack);
    }

    @KafkaListener(topics = "lemuel.ops.payment.failed", groupId = GROUP)
    public void onPaymentFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        recordFailure(DomainEventSignalConsumer.METRIC_PAYMENT, record, ack);
    }

    @KafkaListener(topics = "lemuel.ops.stock.depleted", groupId = GROUP)
    public void onStockDepleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        recordFailure("stock", record, ack);
    }

    /**
     * 배송 후 환불로 보류된 재고의 회수 지연.
     *
     * <p>재고 "부족"(stock)과 다른 버킷에 넣는다 — 부족은 매입·발주로, 지연은 택배 회수 독촉으로
     * 해소되므로 원인도 조치도 다르다. 같은 버킷에 섞으면 실패율이 무엇을 뜻하는지 알 수 없게 된다.
     */
    @KafkaListener(topics = "lemuel.ops.stock.reclaim_delayed", groupId = GROUP)
    public void onStockReclaimDelayed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        recordFailure("stock-reclaim", record, ack);
    }

    @KafkaListener(topics = "lemuel.ops.shipping.delayed", groupId = GROUP)
    public void onShippingDelayed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        recordFailure("shipping", record, ack);
    }

    private void recordFailure(String metricKey, ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            recordSignalUseCase.recordEvent(metricKey, true, resolveOccurredAt(record));
        } catch (Exception e) {
            log.warn("실패 신호 버킷 적재 실패 — 스킵: metricKey={} topic={} offset={}",
                    metricKey, record.topic(), record.offset(), e);
        } finally {
            ack.acknowledge();
        }
    }

    /** envelope.occurredAt 우선, 파싱 실패/부재 시 Kafka record timestamp 폴백. */
    private Instant resolveOccurredAt(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());
            JsonNode occurredAt = node.get("occurredAt");
            if (occurredAt != null && occurredAt.isTextual()) {
                return Instant.parse(occurredAt.asText());
            }
        } catch (Exception ignored) {
            // 폴백으로 진행
        }
        return Instant.ofEpochMilli(record.timestamp());
    }
}
