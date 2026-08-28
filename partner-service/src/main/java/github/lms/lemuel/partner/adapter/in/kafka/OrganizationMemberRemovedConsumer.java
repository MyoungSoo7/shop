package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.partner.application.port.in.RecordDirectoryUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.organization.member_removed} — 접근 회수.
 *
 * <p>이 이벤트가 늦으면 <b>이미 나간 사람이 매출을 계속 본다.</b> 그래서 이 컨슈머는 화면
 * 데이터가 아니라 권한을 다루는 쪽에 가깝다. 대상 행이 없으면(가입 이벤트가 아직 안 옴)
 * 예외 대신 경고를 남긴다 — 재시도해도 가입 이벤트가 오지는 않기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRemovedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";

    private final RecordDirectoryUseCase recordDirectory;

    public OrganizationMemberRemovedConsumer(ProcessedEventRepository processedEventRepository,
                                             ObjectMapper objectMapper,
                                             RecordDirectoryUseCase recordDirectory) {
        super(processedEventRepository, objectMapper);
        this.recordDirectory = recordDirectory;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.organization-member-removed:lemuel.organization.member_removed}",
            groupId = GROUP)
    @Transactional
    public void onMemberRemoved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.organization.member_removed";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordDirectory.memberRemoved(new RecordDirectoryUseCase.MemberRemoved(
                requiredLong(payload, "membershipId", eventId),
                requiredLong(payload, "organizationId", eventId),
                requiredLong(payload, "userId", eventId)));
    }
}
