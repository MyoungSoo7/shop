package github.lms.lemuel.seller.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.seller.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.seller.domain.MemberRole;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.organization.member_role_changed} — 역할 변경.
 *
 * <p>파트너 콘솔에서 이 이벤트는 목록의 라벨을 고치는 것뿐이었다. 여기서는 다르다 —
 * {@link MemberRole#canSubmit()} 이 STAFF 의 상품 등록을 실제로 거절하므로, 강등이 늦게 반영되면
 * 권한을 잃은 사람이 계속 상품을 올린다. 승격이 늦으면 반대로 정당한 사람이 막힌다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRoleChangedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordDirectoryUseCase recordDirectory;

    public OrganizationMemberRoleChangedConsumer(ProcessedEventRepository processedEventRepository,
                                                 ObjectMapper objectMapper,
                                                 RecordDirectoryUseCase recordDirectory) {
        super(processedEventRepository, objectMapper);
        this.recordDirectory = recordDirectory;
    }

    // 한 줄로 붙여 쓴다. 문자열 연결로 쪼개면 컴파일은 되지만 topic-consumer-gate 의 리스너
    // 파서가 토픽을 못 읽어 "이 토픽은 아무도 안 듣는다" 로 집계된다 — 게이트가 조용히 눈이 먼다.
    @KafkaListener(topics = "${app.kafka.topic.organization-member-role-changed:lemuel.organization.member_role_changed}", groupId = GROUP)
    @Transactional
    public void onMemberRoleChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.organization.member_role_changed";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordDirectory.memberRoleChanged(new RecordDirectoryUseCase.MemberRoleChanged(
                requiredLong(payload, "membershipId", eventId),
                requiredLong(payload, "organizationId", eventId),
                requiredLong(payload, "userId", eventId),
                MemberRole.valueOf(requiredText(payload, "newRole", eventId))));
    }
}
