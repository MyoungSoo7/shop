package github.lms.lemuel.marketing.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — 보상 지급 요청이 계약 스키마를 만족해야 한다.
 *
 * <p>이 이벤트의 소비자는 order-service 의 포인트 원장이다. 페이로드가 계약에서 벗어나면
 * 컴파일도 단위 테스트도 통과하고 <b>운영에서 DLT 로만</b> 드러난다 — 그때 남는 증거는
 * "적립이 안 됐다" 는 문의뿐이다. 그 드리프트를 빌드 시점으로 앞당긴다.
 *
 * <p>금액 표현을 따로 못 박는 이유는 이게 조용히 깨지는 종류의 결함이기 때문이다.
 * 공용 매퍼 대신 {@code new ObjectMapper()} 를 쓰면 {@code 1.0E+2} 같은 지수 표기가 나가고,
 * 수신 측 {@code new BigDecimal(text)} 는 그걸 <b>정상 파싱</b>한다 — 값이 100 이면 우연히
 * 맞기까지 한다. 스키마의 {@code pattern} 이 이 표기를 거부하는 것이 유일한 방어선이다.
 */
@ExtendWith(MockitoExtension.class)
class RewardRequestedContractTest {

    private static final String TOPIC = "lemuel.marketing.reward_requested";

    private static final UUID REWARD_ID = UUID.fromString("3f1b5c9a-2d64-4f0e-9a71-8c5e2b7d1f40");
    private static final UUID CAMPAIGN_ID = UUID.fromString("8e2a4d17-6b93-4c25-b0f8-31d7a9e5c6b2");
    private static final UUID REFERENCE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock SaveOutboxEventPort outbox;
    @Mock TraceContextCapture trace;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    private OutboxBackedRewardEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxBackedRewardEventPublisher(outbox, OutboxJson.mapper(), trace);
    }

    @Test
    @DisplayName("출석 목표 보상 페이로드는 계약을 만족한다")
    void attendanceGoalReward_satisfiesContract() {
        publisher.rewardRequested(grant(RewardSource.ATTENDANCE_GOAL, "1000",
                LocalDate.parse("2027-08-24"), "5일 연속 출석 달성"), "8월 출석체크");

        OutboxEvent event = saved();
        assertThat(event.getAggregateType()).isEqualTo("Marketing");
        assertThat(event.getEventType()).isEqualTo("RewardRequested");
        EventContractValidator.assertValid(TOPIC, event.getPayload());
    }

    @Test
    @DisplayName("만료일·적요가 없는 럭키박스 보상도 계약을 만족한다")
    void luckyboxRewardWithoutOptionalFields_satisfiesContract() {
        publisher.rewardRequested(grant(RewardSource.LUCKYBOX, "500", null, null), null);

        EventContractValidator.assertValid(TOPIC, saved().getPayload());
    }

    @Test
    @DisplayName("메시지 키는 보상 id 다 — 카탈로그의 orderingKey 와 같아야 한다")
    void aggregateIdIsRewardId() {
        publisher.rewardRequested(grant(RewardSource.ATTENDANCE_DAILY, "10", null, null), "8월 출석체크");

        assertThat(saved().getAggregateId()).isEqualTo(REWARD_ID.toString());
    }

    @Test
    @DisplayName("금액은 지수 표기가 아닌 평문 문자열로 나간다")
    void amountIsPlainString() {
        // 1E+3 으로 나가면 스키마 pattern 이 거부한다. 그 방어선이 실제로 작동하는지 본다.
        publisher.rewardRequested(grant(RewardSource.LUCKYBOX, "1E+3", null, null), "럭키박스");

        String payload = saved().getPayload();
        assertThat(payload).contains("\"amount\":\"1000\"");
        EventContractValidator.assertValid(TOPIC, payload);
    }

    @Test
    @DisplayName("회원 참조가 숫자가 아니면 발행 시점에 터진다 — 조용히 흘려보내지 않는다")
    void nonNumericMemberRefFailsFast() {
        RewardGrant grant = RewardGrant.requestNow(REWARD_ID, RewardSource.LUCKYBOX, REFERENCE_ID,
                CAMPAIGN_ID, "guest-42", new BigDecimal("100"), null, null);

        // 여기서 안 막으면 userId 가 문자열로 나가고, 원장은 그걸 0 으로 읽는다
        // (asLong 은 파싱 실패 시 예외가 아니라 0 을 준다) — 존재하지 않는 회원에게 적립된다.
        assertThatThrownBy(() -> publisher.rewardRequested(grant, "럭키박스"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guest-42");
    }

    // ---------------------------------------------------------------- 도구

    private OutboxEvent saved() {
        verify(outbox).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    private static RewardGrant grant(RewardSource source, String amount, LocalDate expiresOn, String memo) {
        return RewardGrant.requestNow(REWARD_ID, source, REFERENCE_ID, CAMPAIGN_ID,
                "42", new BigDecimal(amount), expiresOn, memo);
    }
}
