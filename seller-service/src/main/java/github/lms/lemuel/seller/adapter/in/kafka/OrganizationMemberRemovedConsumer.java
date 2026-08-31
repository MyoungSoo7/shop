package github.lms.lemuel.seller.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.seller.application.port.in.RecordDirectoryUseCase;
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
 * <p>이 이벤트가 늦으면 <b>이미 나간 사람이 상품을 등록하고 송장을 찍는다.</b> 조회만 열려 있던
 * 파트너 콘솔과 달리 여기서 지연은 곧 쓰기 권한의 지연이다. 그래서 이 컨슈머는 화면 데이터가
 * 아니라 인가를 다룬다.
 *
 * <p>대상 행이 없으면(가입 이벤트가 아직 안 옴) 예외 대신 경고를 남긴다 — 재시도해도 가입
 * 이벤트가 오지는 않는다. 다만 그 경고는 삼키지 않고 남긴다: 순서가 뒤집혀 도착한 가입 이벤트가
 * 뒤늦게 행을 되살리면, 회수됐어야 할 사람이 되살아나기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRemovedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordDirectoryUseCase recordDirectory;

    public OrganizationMemberRemovedConsumer(ProcessedEventRepository processedEventRepository,
                                             ObjectMapper objectMapper,
                                             RecordDirectoryUseCase recordDirectory) {
        super(processedEventRepository, objectMapper);
        this.recordDirectory = recordDirectory;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-member-removed:lemuel.organization.member_removed}", groupId = GROUP)
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
