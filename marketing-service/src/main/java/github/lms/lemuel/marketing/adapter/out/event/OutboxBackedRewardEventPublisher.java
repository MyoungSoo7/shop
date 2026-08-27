package github.lms.lemuel.marketing.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.marketing.application.port.out.PublishRewardRequestedPort;
import github.lms.lemuel.marketing.domain.RewardGrant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 보상 지급 요청을 outbox 에 적재한다.
 *
 * <p>브로커로 직접 쏘지 않는 이유는 {@link PublishRewardRequestedPort} 주석에 있다 — 요약하면
 * 출석 기록과 지급 요청이 같은 트랜잭션에서 함께 커밋되어야 하기 때문이다.
 */
@Component
public class OutboxBackedRewardEventPublisher implements PublishRewardRequestedPort {

    /** 토픽명은 여기서 파생된다 — lemuel.marketing.reward_requested (카탈로그 등재명). */
    private static final String AGGREGATE_TYPE = "Marketing";

    private final SaveOutboxEventPort outbox;
    private final ObjectMapper mapper;
    private final TraceContextCapture trace;

    /**
     * 매퍼는 반드시 {@code outboxObjectMapper} 다.
     *
     * <p>{@code new ObjectMapper()} 를 쓰면 {@code expiresOn}(LocalDate)·{@code occurredAt}(Instant)
     * 직렬화가 터지고, 금액이 {@code 1.0E+2} 같은 지수 표기로 나가 수신 측에서 포인트가 달라진다.
     * 공용 매퍼에 JavaTimeModule 과 금액 plain string 설정이 들어 있다.
     */
    public OutboxBackedRewardEventPublisher(SaveOutboxEventPort outbox,
                                            @Qualifier("outboxObjectMapper") ObjectMapper mapper,
                                            TraceContextCapture trace) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.trace = trace;
    }

    @Override
    public void rewardRequested(RewardGrant grant, String campaignName) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rewardId", grant.id());
            // memberRef 는 도메인 안에서는 그냥 문자열이다 — 마케팅 도메인이 회원 식별자의 타입을
            // 알 필요가 없기 때문이다. 하지만 경계를 넘는 순간에는 수신 측이 쓰는 타입이어야 한다.
            // order-service 의 GrantPointCommand 는 Long userId 를 받는다. 여기서 숫자로 못 바꾸면
            // 그 보상은 어차피 지급될 수 없으므로, 조용히 문자열로 흘려보내지 않고 여기서 터뜨린다.
            payload.put("userId", parseUserId(grant.memberRef()));
            payload.put("campaignId", grant.campaignId());
            payload.put("campaignName", campaignName);
            payload.put("source", grant.source().name());
            payload.put("amount", grant.amount());
            payload.put("expiresOn", grant.expiresOn());
            payload.put("memo", grant.memo());
            payload.put("requestedAt", grant.requestedAt());
            // 메시지 키는 지역변수 이름으로 의미를 남긴다 — 카탈로그의 orderingKey(rewardId)와
            // 대조하는 kafka-publisher-gate 가 이 이름을 읽는다(인라인하면 힌트가 toString 이 된다).
            //
            // 키가 보상 id 인 것이 중요하다. 회원 id 로 묶으면 같은 사람의 출석 보상과 럭키박스
            // 보상이 한 파티션에 줄을 서서 앞의 하나가 막히면 뒤가 전부 밀린다. 보상끼리는
            // 순서 의존이 없고, 중복 방지는 수신 측이 referenceId 로 한다.
            String rewardId = grant.id().toString();
            outbox.save(OutboxEvent.pending(AGGREGATE_TYPE, rewardId,
                    "RewardRequested", mapper.writeValueAsString(payload), trace.captureCurrentTraceParent()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize RewardRequested payload", exception);
        }
    }

    private static long parseUserId(String memberRef) {
        try {
            return Long.parseLong(memberRef);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "memberRef 가 숫자 회원 id 가 아니다 — 포인트 지급 요청을 만들 수 없다: " + memberRef, exception);
        }
    }
}
