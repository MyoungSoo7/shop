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
 * {@code lemuel.product.registered} — 우리가 낸 요청의 <b>회신</b>. 이 서비스가 받는 아홉 중
 * 유일하게 우리 원본(신청서)의 상태를 바꾸는 이벤트다.
 *
 * <p>흐름은 이렇다. 운영자가 승인하면 신청서는 APPROVED 가 되고 상품번호는 <b>비어 있다</b>.
 * outbox 가 {@code lemuel.seller.product_approved} 를 내보내고, order-service 가 카탈로그에
 * 상품을 만든 뒤 이 토픽으로 돌려준다. 그때 비로소 신청서에 상품번호가 붙는다.
 *
 * <p>승인 시점에 상품번호를 지어내지 않는 이유가 이것이다 — 지어내면 등록이 실패한 건과
 * 성공한 건을 구분할 수 없고, 셀러는 "등록됐다" 는 화면을 보면서 몰에 없는 상품을 판다.
 *
 * <p>{@code submissionId} 는 필수다. 없으면 이 회신이 어느 신청서의 것인지 알 수 없고, 그
 * 상태로 처리하면 신청서 하나가 영영 "등록 처리 중" 에 남는다. 그래서 DLT 로 보낸다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ProductRegisteredConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordCommerceUseCase recordCommerce;

    public ProductRegisteredConsumer(ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper,
                                     RecordCommerceUseCase recordCommerce) {
        super(processedEventRepository, objectMapper);
        this.recordCommerce = recordCommerce;
    }

    @KafkaListener(topics = "${app.kafka.topic.product-registered:lemuel.product.registered}", groupId = GROUP)
    @Transactional
    public void onProductRegistered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.product.registered";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordCommerce.productRegistered(new RecordCommerceUseCase.ProductRegistered(
                requiredLong(payload, "productId", eventId),
                Payloads.text(payload, "name"),
                requiredLong(payload, "submissionId", eventId),
                requiredLong(payload, "sellerId", eventId)));
    }
}
