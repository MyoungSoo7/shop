package github.lms.lemuel.shipping.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.seller.shipment_registered} → 출고 처리.
 *
 * <h2>소유 확인은 여기서 하지 않는다</h2>
 * order-service 는 셀러를 모른다 — 주문에 셀러 컬럼이 없다. "이 주문이 저 셀러의 것인가" 는
 * seller-service 가 자기 사본으로 판단하고, 통과한 것만 이 토픽에 실린다. 여기서 한 번 더
 * 확인하려면 셀러 개념을 order 로 들여와야 하고, 그 순간 두 서비스가 같은 사실을 각자 들고
 * 있으면서 서로 다른 답을 낼 수 있게 된다. {@code sellerId} 를 payload 로 받되 <b>추적용으로만</b>
 * 쓰는 이유다.
 *
 * <h2>중복</h2>
 * 세 겹이다. 같은 {@code event_id} 재전달은 {@code processed_events} 가 막고, 셀러가 버튼을 두 번
 * 눌러 생기는 새 이벤트는 seller-service 쪽 주문당 유니크 제약이 애초에 발행을 막는다. 그마저
 * 뚫려도 {@code Shipment} 가 PENDING/READY 에서만 출고를 허용하므로 이미 나간 건의 송장번호가
 * 덮이지 않는다.
 *
 * <h2>실패</h2>
 * 배송 정보가 아직 없거나 이미 출고·배송완료된 주문이면 {@code ShipmentInvariantViolationException}
 * 이 올라가 재시도 후 DLT 로 간다. 조용히 ack 하면 셀러 화면에는 송장이 등록된 것으로 남고
 * 주문은 출고되지 않은 채 남아, 그 불일치를 아무도 보지 못한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SellerShipmentRegisteredConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-order";
    private static final String EVENT_TYPE = "SellerShipmentRegistered";

    private final ShippingUseCase shippingUseCase;

    public SellerShipmentRegisteredConsumer(ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper,
                                            ShippingUseCase shippingUseCase) {
        super(processedEventRepository, objectMapper);
        this.shippingUseCase = shippingUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.seller-shipment-registered:lemuel.seller.shipment_registered}",
            groupId = GROUP)
    @Transactional
    public void onSellerShipmentRegistered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return EVENT_TYPE;
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long orderId = requiredLong(payload, "orderId", eventId);
        long sellerId = requiredLong(payload, "sellerId", eventId);
        String carrier = requiredText(payload, "carrier", eventId);
        String trackingNumber = requiredText(payload, "trackingNumber", eventId);

        shippingUseCase.ship(orderId, carrier, trackingNumber);

        log.info("셀러 송장 등록으로 출고 처리: orderId={}, sellerId={}, carrier={}",
                orderId, sellerId, carrier);
    }
}
