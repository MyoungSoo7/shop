package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.partner.application.port.in.RecordCatalogUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.order.created} — 결제 행에 상품과 주문상태를 붙여 주는 보조 입력.
 *
 * <p>이 이벤트만으로는 매출을 세지 않는다. 주문은 셀러를 싣지 않으므로 "누구 매출인지" 를 알 수
 * 없고, 결제되지 않은 주문까지 섞인다. 결제 이벤트가 정본이고 이건 라벨이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderCreatedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";

    private final RecordCatalogUseCase recordCatalog;

    public OrderCreatedConsumer(ProcessedEventRepository processedEventRepository,
                                ObjectMapper objectMapper,
                                RecordCatalogUseCase recordCatalog) {
        super(processedEventRepository, objectMapper);
        this.recordCatalog = recordCatalog;
    }

    @KafkaListener(topics = "${app.kafka.topic.order-created:lemuel.order.created}", groupId = GROUP)
    @Transactional
    public void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.order.created";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordCatalog.orderCreated(new RecordCatalogUseCase.OrderCreated(
                requiredLong(payload, "orderId", eventId),
                requiredLong(payload, "userId", eventId),
                Payloads.longOrNull(payload, "productId"),
                requiredText(payload, "status", eventId),
                requiredDecimal(payload, "amount", eventId),
                Payloads.localDateTimeOrNull(payload, "createdAt")));
    }
}
