package github.lms.lemuel.seller.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.seller.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.seller.domain.OrgType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.organization.created} — 셀러 조직 자체.
 *
 * <p>조직↔셀러 연결({@code externalRef} → {@code seller_id})이 여기서 정해진다. 파트너 콘솔에서
 * 이 연결이 없으면 매출 조회가 안 됐을 뿐이지만, 여기서는 <b>상품 등록이 안 된다</b> — 셀러
 * 번호 없이는 누구 이름으로 파는 물건인지 정할 수 없기 때문이다.
 *
 * <p>{@code externalRef} 가 숫자로 안 끝나면 셀러 번호는 null 로 남는다. 0 이나 -1 로 메우지
 * 않는 이유는 그 순간 서로 다른 조직이 한 셀러로 뭉치고, 남의 상품이 내 목록에 뜨기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationCreatedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordDirectoryUseCase recordDirectory;

    public OrganizationCreatedConsumer(ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper,
                                       RecordDirectoryUseCase recordDirectory) {
        super(processedEventRepository, objectMapper);
        this.recordDirectory = recordDirectory;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-created:lemuel.organization.created}", groupId = GROUP)
    @Transactional
    public void onOrganizationCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.organization.created";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordDirectory.organizationCreated(new RecordDirectoryUseCase.OrganizationCreated(
                requiredLong(payload, "organizationId", eventId),
                requiredText(payload, "name", eventId),
                OrgType.valueOf(requiredText(payload, "type", eventId)),
                Payloads.text(payload, "externalRef"),
                requiredLong(payload, "ownerUserId", eventId)));
    }
}
