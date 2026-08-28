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
 * {@code lemuel.product.changed} — 상품 ID 를 사람이 읽는 이름으로 바꾸기 위한 것뿐이다.
 *
 * <p>계약상 {@code name} 은 <b>필수이지만 값이 null 일 수 있다</b>({@code ["string","null"]}).
 * 그래서 {@code required*} 를 쓰지 않는다 — null 을 계약 위반으로 보고 DLT 로 보내면
 * 정상 이벤트가 격리된다. null 이면 이름 없이 두고 화면이 상품 ID 로 대체 표기한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ProductChangedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";

    private final RecordCatalogUseCase recordCatalog;

    public ProductChangedConsumer(ProcessedEventRepository processedEventRepository,
                                  ObjectMapper objectMapper,
                                  RecordCatalogUseCase recordCatalog) {
        super(processedEventRepository, objectMapper);
        this.recordCatalog = recordCatalog;
    }

    @KafkaListener(topics = "${app.kafka.topic.product-changed:lemuel.product.changed}", groupId = GROUP)
    @Transactional
    public void onProductChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.product.changed";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordCatalog.productChanged(
                requiredLong(payload, "productId", eventId),
                Payloads.text(payload, "name"));
    }
}
