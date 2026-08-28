package github.lms.lemuel.point.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — marketing 이 낸 보상 요청을 원장이 어떻게 읽는가.
 *
 * <p>이 컨슈머는 order-service 최초의 리스너이고, 지금까지 테스트가 <b>한 건도</b> 없었다.
 * 여기서 잘못 읽으면 결과는 "적립 실패" 가 아니라 <b>잘못된 적립</b>이다 — 금액이 다르거나,
 * 만료일이 하루 어긋나거나, 남의 계정에 들어간다. 그런 종류는 원장에서 사후에 되돌리기 어렵다.
 *
 * <p>입력은 손으로 쓴 JSON 이 아니라 정본 샘플이다. 발행 측(marketing)이 필드를 바꾸면 샘플이
 * 바뀌고 그 변경이 여기까지 온다. 손으로 쓴 JSON 은 옛 모양을 영원히 통과시킨다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketingRewardConsumerTest {

    private static final String TOPIC = "lemuel.marketing.reward_requested";
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String REWARD_ID = "3f1b5c9a-2d64-4f0e-9a71-8c5e2b7d1f40";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock ProcessedEventRepository processedEvents;
    @Mock GrantPointUseCase grantPoint;
    @Mock Acknowledgment ack;
    @Captor ArgumentCaptor<GrantPointUseCase.GrantPointCommand> commandCaptor;

    private MarketingRewardConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MarketingRewardConsumer(processedEvents, MAPPER, grantPoint);
        when(processedEvents.existsById(any())).thenReturn(false);
    }

    @Test
    @DisplayName("정본 샘플은 그대로 적립 명령이 된다 — 여덟 필드 전부")
    void canonicalSampleBecomesGrantCommand() {
        String sample = EventContractValidator.canonicalSample(TOPIC);
        EventContractValidator.assertValid(TOPIC, sample);

        consumer.onRewardRequested(record(sample), ack);

        GrantPointUseCase.GrantPointCommand command = captured();
        assertThat(command.userId()).isEqualTo(42L);
        assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(command.origin()).isEqualTo(PointLotOrigin.PROMOTION_REWARD);
        // 멱등 키의 두 조각이다. 이 짝이 흔들리면 재전달이 이중 적립이 된다.
        assertThat(command.referenceType()).isEqualTo("ATTENDANCE_GOAL");
        assertThat(command.referenceId()).isEqualTo(REWARD_ID);
        assertThat(command.actor()).isEqualTo("marketing-service");
        assertThat(command.memo()).isEqualTo("8월 출석체크 (ATTENDANCE_GOAL)");
        verify(ack).acknowledge();
        verify(processedEvents).save(any(ProcessedEventJpaEntity.class));
    }

    @Test
    @DisplayName("만료일은 그날 자정까지 — KST 기준으로 하루를 더한다")
    void expiryIsEndOfDayInSeoul() {
        consumer.onRewardRequested(record(EventContractValidator.canonicalSample(TOPIC)), ack);

        // "2027-08-24 까지 유효" 는 24일 23:59:59 까지다. 25일 00:00+09:00 이 그 경계다.
        // UTC 로 잘못 읽으면 한국 사용자에게는 24일 오전 9시에 포인트가 사라진다.
        assertThat(captured().expiresAt())
                .isEqualTo(OffsetDateTime.parse("2027-08-25T00:00:00+09:00"));
    }

    @Test
    @DisplayName("만료일이 없으면 무기한 로트가 된다")
    void missingExpiryMeansNoExpiry() {
        consumer.onRewardRequested(record(sampleWithout("expiresOn")), ack);

        assertThat(captured().expiresAt()).isNull();
    }

    @Test
    @DisplayName("캠페인 이름 필드가 아예 없으면 적요는 보상 종류로 대체된다")
    void memoFallsBackToSource() {
        consumer.onRewardRequested(record(sampleWithout("campaignName")), ack);

        assertThat(captured().memo()).isEqualTo("이벤트 보상 (ATTENDANCE_GOAL)");
    }

    @Test
    @DisplayName("캠페인 이름이 명시적 null 로 와도 마찬가지다 — 발행 측이 실제로 보내는 모양이다")
    void explicitNullCampaignNameFallsBackToo() {
        // 발행 측은 필드를 빼지 않고 null 을 싣는다. 일괄 지급 경로에서 캠페인을 못 찾고
        // 보상 메모까지 없으면 이 값이 null 이 된다. NullNode 를 걸러내지 않으면
        // asText() 가 "null" 을 돌려주고, 적요가 "null (ATTENDANCE_GOAL)" 로 남는다.
        consumer.onRewardRequested(record(sampleWithNull("campaignName")), ack);

        assertThat(captured().memo()).isEqualTo("이벤트 보상 (ATTENDANCE_GOAL)");
    }

    @Test
    @DisplayName("0 포인트 요청은 적립하지 않고 터뜨린다 — ack 하지 않는다")
    void zeroAmountIsRejected() {
        ConsumerRecord<String, String> record = record(sampleWith("amount", "0"));

        assertThatThrownBy(() -> consumer.onRewardRequested(record, ack))
                .isInstanceOf(IllegalArgumentException.class);

        // 받아 주면 금액 0 짜리 로트가 쌓여 "지급됐다" 는 이력만 남고 잔액은 그대로다.
        verifyNoInteractions(grantPoint);
        verify(ack, never()).acknowledge();
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("필수 필드가 빠지면 적립하지 않고 터뜨린다")
    void missingRequiredFieldIsRejected() {
        ConsumerRecord<String, String> record = record(sampleWithout("userId"));

        assertThatThrownBy(() -> consumer.onRewardRequested(record, ack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        verifyNoInteractions(grantPoint);
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("같은 event_id 가 다시 오면 적립하지 않는다")
    void duplicateDeliveryIsSkipped() {
        when(processedEvents.existsById(any())).thenReturn(true);

        consumer.onRewardRequested(record(EventContractValidator.canonicalSample(TOPIC)), ack);

        verifyNoInteractions(grantPoint);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("event_id 헤더가 없으면 적립하지 않고 ack 한다 — 재전달해도 같은 결과라서다")
    void recordWithoutEventIdIsSkipped() {
        ConsumerRecord<String, String> headerless = new ConsumerRecord<>(
                TOPIC, 0, 0L, "key", EventContractValidator.canonicalSample(TOPIC));

        consumer.onRewardRequested(headerless, ack);

        verifyNoInteractions(grantPoint);
        verify(ack).acknowledge();
    }

    // ---------------------------------------------------------------- 도구

    private GrantPointUseCase.GrantPointCommand captured() {
        verify(grantPoint).grant(commandCaptor.capture());
        return commandCaptor.getValue();
    }

    private static String sampleWithout(String field) {
        ObjectNode node = sampleNode();
        node.remove(field);
        return write(node);
    }

    private static String sampleWithNull(String field) {
        ObjectNode node = sampleNode();
        node.putNull(field);
        return write(node);
    }

    private static String sampleWith(String field, String value) {
        ObjectNode node = sampleNode();
        node.put(field, value);
        return write(node);
    }

    private static ObjectNode sampleNode() {
        try {
            return (ObjectNode) MAPPER.readTree(EventContractValidator.canonicalSample(TOPIC));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConsumerRecord<String, String> record(String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, REWARD_ID, payload);
        record.headers().add("event_id", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
