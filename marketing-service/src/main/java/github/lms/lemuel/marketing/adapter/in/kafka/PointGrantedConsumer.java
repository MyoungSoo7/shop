package github.lms.lemuel.marketing.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.marketing.application.port.in.ConfirmRewardUseCase;
import github.lms.lemuel.marketing.domain.RewardSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@code lemuel.point.granted} → 보상 확정.
 *
 * <h2>왜 이 컨슈머가 필요한가</h2>
 * 우리는 포인트를 직접 주지 않는다. 원장은 order-service 의 것이고, 우리는 요청만 낸다
 * ({@code lemuel.marketing.reward_requested}). 요청을 보낸 것과 실제로 적립된 것은 다르다 —
 * 브로커가 삼켰을 수도, 수신 측이 계정을 못 찾았을 수도 있다. 이 컨슈머가 없으면
 * {@code reward_grants} 는 영원히 REQUESTED 에 머물고, "포인트 왜 안 들어왔냐" 는 문의가 왔을 때
 * 운영자가 볼 수 있는 것은 우리가 요청을 냈다는 사실뿐이다.
 *
 * <h2>남의 적립은 조용히 흘린다</h2>
 * 이 토픽에는 충전 보너스·수기 지급 등 마케팅과 무관한 적립이 훨씬 많이 흐른다.
 * 우리 것을 가르는 기준은 {@code referenceType} 이 {@link RewardSource} 값인가 하나뿐이다 —
 * 그 짝이 원장 쪽 멱등 키이기도 하다. {@code referenceId} 의 UUID 여부만 보고 거르지 않는 이유는,
 * 남의 슬라이스가 UUID 참조를 쓰기 시작하는 순간 그 판정이 조용히 무의미해지기 때문이다.
 * 경고를 찍지 않는 것은 의도다 — 정상 트래픽 대부분이 여기 해당해서, 경고로 찍으면 로그가
 * 남의 이벤트로 뒤덮여 진짜 문제가 묻힌다.
 *
 * <h2>ack 규칙</h2>
 * 실패하면 ack 하지 않는다. 확정 처리가 유실되면 이미 지급된 포인트가 우리 장부에서는 미확정으로
 * 남고, 정산 스케줄러가 그걸 다시 요청할 여지가 생긴다. 롤백되면 재전달되고, 멱등 처리
 * ({@code processed_events})가 있으므로 재전달은 안전하다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PointGrantedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-marketing";
    private static final String EVENT_TYPE = "PointGranted";

    private final ConfirmRewardUseCase confirmRewardUseCase;

    public PointGrantedConsumer(ProcessedEventRepository processedEventRepository,
                                ObjectMapper objectMapper,
                                ConfirmRewardUseCase confirmRewardUseCase) {
        super(processedEventRepository, objectMapper);
        this.confirmRewardUseCase = confirmRewardUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.point-granted:lemuel.point.granted}", groupId = GROUP)
    @Transactional
    public void onPointGranted(ConsumerRecord<String, String> record, Acknowledgment ack) {
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
        if (!isOurs(payload.get("referenceType"))) {
            return;
        }
        JsonNode referenceId = payload.get("referenceId");
        if (referenceId == null || referenceId.isNull() || referenceId.asText().isBlank()) {
            return;
        }
        UUID rewardId;
        try {
            rewardId = UUID.fromString(referenceId.asText());
        } catch (IllegalArgumentException malformed) {
            // referenceType 은 우리 것인데 참조가 UUID 가 아니다 — 계약 위반이라 조용히 넘기면 안 된다.
            // 던지면 ack 되지 않고 재전달되며, 반복되면 DLQ 로 간다(사람이 보게 된다).
            throw new IllegalStateException(
                    "우리 referenceType 인데 referenceId 가 UUID 가 아니다: " + referenceId.asText(), malformed);
        }
        confirmRewardUseCase.confirm(rewardId);
    }

    /** {@code referenceType} 이 {@link RewardSource} 값인가 — 아니면 남의 적립이다. */
    private static boolean isOurs(JsonNode referenceType) {
        if (referenceType == null || referenceType.isNull()) {
            return false;
        }
        String value = referenceType.asText();
        for (RewardSource source : RewardSource.values()) {
            if (source.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
