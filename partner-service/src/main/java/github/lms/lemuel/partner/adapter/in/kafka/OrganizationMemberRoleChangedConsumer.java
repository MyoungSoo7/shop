package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.partner.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.partner.domain.MemberRole;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.organization.member_role_changed} — 구성원 역할 표기 갱신.
 *
 * <p>지금 이 서비스의 API 는 전부 읽기이고 역할로 갈리는 것이 하나도 없다. 그래도 받아 두는
 * 이유는 구성원 목록에 역할을 보여주기 때문이고, 나중에 역할로 무언가를 막게 될 때 그 데이터가
 * 이미 최신이어야 하기 때문이다. 역할이 권한처럼 보이지만 아직 아무것도 막지 않는다는 점은
 * {@link MemberRole} 에 적어 두었다 — 막는다고 착각하면 그게 곧 구멍이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRoleChangedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";

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
