package github.lms.lemuel.seller.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.seller.application.port.in.RecordCommerceUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.order.created} — 결제 행에 <b>상품과 주문상태</b>를 붙여 주는 보조 입력.
 *
 * <p>이 이벤트만으로는 주문 목록을 만들지 않는다. 주문은 셀러를 싣지 않으므로 "누구 주문인지" 를
 * 알 수 없고, 결제되지 않은 주문까지 섞인다. 결제 이벤트가 정본이고 이건 라벨이다.
 *
 * <p>다만 여기서 오는 {@code productId} 가 셀러 화면의 상품명 표기를 좌우한다 — 이게 없으면
 * 주문은 뜨지만 무슨 상품인지 알 수 없어, 셀러가 포장할 물건을 고르지 못한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderCreatedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordCommerceUseCase recordCommerce;

    public OrderCreatedConsumer(ProcessedEventRepository processedEventRepository,
                                ObjectMapper objectMapper,
                                RecordCommerceUseCase recordCommerce) {
        super(processedEventRepository, objectMapper);
        this.recordCommerce = recordCommerce;
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
        recordCommerce.orderCreated(new RecordCommerceUseCase.OrderCreated(
                requiredLong(payload, "orderId", eventId),
                requiredLong(payload, "userId", eventId),
                Payloads.longOrNull(payload, "productId"),
                requiredText(payload, "status", eventId),
                requiredDecimal(payload, "amount", eventId),
                Payloads.localDateTimeOrNull(payload, "createdAt")));
    }
}
