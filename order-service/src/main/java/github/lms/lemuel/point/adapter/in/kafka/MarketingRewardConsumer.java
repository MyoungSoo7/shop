package github.lms.lemuel.point.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * {@code lemuel.marketing.reward_requested} → 포인트 적립.
 *
 * <h2>이 클래스가 order-service 최초의 {@code @KafkaListener} 다</h2>
 * 그동안 order 는 발행만 했다. {@code application.yml} 의 {@code group-id: lemuel-order} 는
 * 바로 이 순간을 대비해 미리 정정해 둔 값이다 — 모놀리스에서 분리될 때 남아 있던
 * {@code lemuel-settlement} 그대로 리스너가 붙었다면 정산과 같은 컨슈머 그룹이 되어 파티션을
 * 나눠 갖고 오프셋까지 공유했을 것이다(정산 이벤트가 조용히 사라진다). 리스너가 0건일 때
 * 고쳐 뒀기 때문에 지금 이관할 오프셋이 없다.
 *
 * <h2>왜 marketing 이 직접 적립하지 않는가</h2>
 * 포인트 원장은 회계 장부다. 잔액·로트·소멸·환불 복원이 한 트랜잭션 안에서 맞아야 하고,
 * GL 분개가 여기서 나온다. 원장을 둘로 쪼개면 "어느 쪽이 맞는 잔액인가" 라는 질문에 답이 없어진다.
 * 그래서 marketing 은 <b>요청</b>만 내고, 적립은 여기서만 일어난다.
 *
 * <h2>멱등</h2>
 * 두 겹이다. {@code processed_events} 가 같은 {@code event_id} 재전달을 막고, 그걸 통과해도
 * {@link GrantPointUseCase} 가 (계좌, GRANT, referenceType, referenceId) 로 이미 만들어진 적립을
 * 다시 만들지 않는다. 두 번째 겹이 필요한 이유는 재전달만이 중복의 원인이 아니기 때문이다 —
 * marketing 의 정산 스케줄러가 미확정 보상을 다시 요청하면 {@code event_id} 는 새 값이다.
 *
 * <h2>실패 처리</h2>
 * 회원이 없거나 금액이 음수인 요청은 재시도해도 영원히 실패한다. 예외를 던져 ack 하지 않고
 * DLT 로 보낸다 — 사람이 봐야 하는 상태다. 조용히 ack 하면 marketing 쪽 보상은 영원히
 * REQUESTED 로 남고, 그 상태는 "브로커가 늦다" 와 구분되지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class MarketingRewardConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-order";
    private static final String EVENT_TYPE = "RewardRequested";

    /** 만료일은 날짜로 오는데 원장은 시각을 쓴다. 그날 자정까지 유효한 것으로 읽는다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GrantPointUseCase grantPointUseCase;

    public MarketingRewardConsumer(ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   GrantPointUseCase grantPointUseCase) {
        super(processedEventRepository, objectMapper);
        this.grantPointUseCase = grantPointUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.marketing-reward-requested:lemuel.marketing.reward_requested}",
            groupId = GROUP)
    @Transactional
    public void onRewardRequested(ConsumerRecord<String, String> record, Acknowledgment ack) {
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
        long userId = requiredLong(payload, "userId", eventId);
        BigDecimal amount = requiredDecimal(payload, "amount", eventId);
        String rewardId = requiredText(payload, "rewardId", eventId);
        String source = requiredText(payload, "source", eventId);

        if (amount.signum() <= 0) {
            // 0 이하 적립은 원장에서 의미가 없다. 받아 주면 금액 0 짜리 로트가 쌓여
            // "지급됐다" 는 이력만 남고 잔액은 그대로다 — 문의가 왔을 때 가장 헷갈리는 상태다.
            throw new IllegalArgumentException(
                    "적립 금액이 0 이하다: eventId=" + eventId + ", rewardId=" + rewardId + ", amount=" + amount);
        }

        grantPointUseCase.grant(new GrantPointUseCase.GrantPointCommand(
                userId,
                amount,
                PointLotOrigin.PROMOTION_REWARD,
                source,
                rewardId,
                expiresAt(payload),
                "marketing-service",
                memo(payload, source)));
    }

    /** {@code expiresOn} 이 없으면 null — 원장의 무기한 로트가 된다. */
    private static OffsetDateTime expiresAt(JsonNode payload) {
        JsonNode expiresOn = payload.get("expiresOn");
        if (expiresOn == null || expiresOn.isNull() || expiresOn.asText().isBlank()) {
            return null;
        }
        return LocalDate.parse(expiresOn.asText()).plusDays(1).atStartOfDay(KST).toOffsetDateTime();
    }

    /**
     * 적요 — 캠페인 이름을 그대로 쓴다.
     *
     * <p>발행 시점의 <b>스냅샷</b>이라는 게 중요하다. 캠페인 이름이 나중에 바뀌어도 이미 나간
     * 적립의 적요는 그대로여야 한다. 여기서 캠페인을 조회해 이름을 붙이면(=코드 의존) 그 불변식이
     * 깨질뿐더러 두 서비스가 한 덩어리가 된다.
     */
    private static String memo(JsonNode payload, String source) {
        JsonNode campaignName = payload.get("campaignName");
        if (campaignName == null || campaignName.isNull() || campaignName.asText().isBlank()) {
            return "이벤트 보상 (" + source + ")";
        }
        return campaignName.asText() + " (" + source + ")";
    }
}
