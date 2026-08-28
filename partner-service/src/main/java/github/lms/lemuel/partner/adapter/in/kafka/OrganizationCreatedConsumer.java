package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.partner.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.partner.domain.OrgType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.organization.created} — 파트너 조직 자체.
 *
 * <p>이 이벤트가 없으면 로그인해도 볼 화면이 없다. 조직↔셀러 연결({@code externalRef} →
 * {@code seller_id})이 여기서 정해지고, 그게 없으면 매출 조회가 아예 시작되지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationCreatedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";

    private final RecordDirectoryUseCase recordDirectory;

    public OrganizationCreatedConsumer(ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper,
                                       RecordDirectoryUseCase recordDirectory) {
        super(processedEventRepository, objectMapper);
        this.recordDirectory = recordDirectory;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-created:lemuel.organization.created}",
            groupId = GROUP)
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
