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
 * {@code lemuel.organization.member_joined} — 로그인한 사람과 조직을 잇는 유일한 연결.
 *
 * <p>이 행이 없으면 그 사용자는 토큰이 멀쩡해도 셀러 API 에서 403 을 받는다. 그게 맞다 —
 * "어느 조직 소속인지" 를 요청이 주장하게 두면, 파트너 콘솔에서는 남의 매출을 <i>보는</i> 데
 * 그쳤겠지만 여기서는 <b>남의 이름으로 상품을 등록</b>할 수 있다.
 *
 * <p>역할도 여기서 온다. 파트너 콘솔의 역할은 표기일 뿐이었지만 이 서비스에서는 실제로 막는다 —
 * {@link MemberRole#canSubmit()} 이 STAFF 의 상품 등록을 거절한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberJoinedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordDirectoryUseCase recordDirectory;

    public OrganizationMemberJoinedConsumer(ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper,
                                            RecordDirectoryUseCase recordDirectory) {
        super(processedEventRepository, objectMapper);
        this.recordDirectory = recordDirectory;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-member-joined:lemuel.organization.member_joined}", groupId = GROUP)
    @Transactional
    public void onMemberJoined(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.organization.member_joined";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        recordDirectory.memberJoined(new RecordDirectoryUseCase.MemberJoined(
                requiredLong(payload, "membershipId", eventId),
                requiredLong(payload, "organizationId", eventId),
                requiredLong(payload, "userId", eventId),
                MemberRole.valueOf(requiredText(payload, "role", eventId))));
    }
}
