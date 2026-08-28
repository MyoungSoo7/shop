package github.lms.lemuel.marketing.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.marketing.application.port.in.ConfirmRewardUseCase;
import github.lms.lemuel.marketing.domain.RewardSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — {@code lemuel.point.granted} 를 우리가 어떻게 읽는가.
 *
 * <p>이 저장소에는 프로듀서 계약 테스트가 8종 있었지만 <b>컨슈머 쪽은 한 건도 없었다</b>.
 * 그래서 검증되던 것은 "우리가 무엇을 내보내는가" 뿐이고, "남이 보낸 것을 우리가 어떻게 읽는가"
 * 는 아무도 재지 않았다. 계약이 깨지는 지점은 대개 후자다.
 *
 * <p>입력으로 정본 샘플({@code contracts/events/samples/})을 쓰는 것이 핵심이다. 손으로 만든
 * JSON 을 쓰면 발행 측이 필드를 바꿔도 이 테스트는 옛 모양을 계속 통과시킨다 — 테스트가
 * 초록인 채로 계약이 갈라진다. 정본 샘플을 쓰면 발행 측 변경이 여기까지 전파된다.
 *
 * <p>가장 중요한 판정은 <b>남의 적립을 흘리는가</b>다. 이 토픽에는 충전 보너스·수기 지급 등
 * 마케팅과 무관한 적립이 훨씬 많이 흐른다. 그걸 예외로 만들면 정상 트래픽이 통째로 DLQ 로 간다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PointGrantedConsumerTest {

    private static final String TOPIC = "lemuel.point.granted";
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REWARD_ID = UUID.fromString("3f1b5c9a-2d64-4f0e-9a71-8c5e2b7d1f40");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock ProcessedEventRepository processedEvents;
    @Mock ConfirmRewardUseCase confirmReward;
    @Mock Acknowledgment ack;

    private PointGrantedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PointGrantedConsumer(processedEvents, MAPPER, confirmReward);
        when(processedEvents.existsById(any())).thenReturn(false);
    }

    @Test
    @DisplayName("정본 샘플(충전 보너스)은 남의 적립이므로 확정하지 않고 ack 한다")
    void canonicalSampleIsSomeoneElsesGrant() {
        String sample = EventContractValidator.canonicalSample(TOPIC);
        // 샘플 자체가 계약을 만족하는지 먼저 확인한다 — 아니면 아래 판정이 무의미하다.
        EventContractValidator.assertValid(TOPIC, sample);

        consumer.onPointGranted(record(sample), ack);

        verifyNoInteractions(confirmReward);
        verify(ack).acknowledge();
        // 남의 이벤트도 처리 완료로 기록한다 — 안 하면 재전달마다 같은 판정을 반복한다.
        verify(processedEvents).save(any(ProcessedEventJpaEntity.class));
    }

    @ParameterizedTest
    @EnumSource(RewardSource.class)
    @DisplayName("우리 보상 종류로 돌아온 적립은 전부 확정한다")
    void ourGrantsAreConfirmed(RewardSource source) {
        String payload = sampleWith(source.name(), REWARD_ID.toString());
        EventContractValidator.assertValid(TOPIC, payload);

        consumer.onPointGranted(record(payload), ack);

        verify(confirmReward).confirm(REWARD_ID);
        verify(ack).acknowledge();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARGE", "ORDER_EARN", "MANUAL_GRANT", "REFUND_RESTORE", "PROMOTION"})
    @DisplayName("우리 것이 아닌 referenceType 은 조용히 흘린다")
    void foreignReferenceTypesAreIgnored(String referenceType) {
        // "PROMOTION" 이 여기 섞여 있는 건 의도다. 계약 문서가 한때 이 값을 쓴다고 적어 뒀는데,
        // 실제로 오가는 값은 보상 종류 이름이다. 저 문구를 믿고 발행 측을 바꾸면 확정이 전멸한다.
        consumer.onPointGranted(record(sampleWith(referenceType, REWARD_ID.toString())), ack);

        verifyNoInteractions(confirmReward);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("우리 referenceType 인데 참조가 UUID 가 아니면 터뜨린다 — ack 하지 않는다")
    void ourTypeWithMalformedReferenceIsFatal() {
        ConsumerRecord<String, String> record = record(sampleWith("LUCKYBOX", "not-a-uuid"));

        assertThatThrownBy(() -> consumer.onPointGranted(record, ack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-a-uuid");

        verifyNoInteractions(confirmReward);
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("이미 처리한 이벤트는 다시 확정하지 않는다")
    void duplicateDeliveryIsSkipped() {
        when(processedEvents.existsById(any())).thenReturn(true);

        consumer.onPointGranted(record(sampleWith("LUCKYBOX", REWARD_ID.toString())), ack);

        verifyNoInteractions(confirmReward);
        verify(ack).acknowledge();
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("계약 스키마의 source 목록과 RewardSource 는 정확히 같다")
    void schemaSourceEnumMatchesDomain() throws Exception {
        // 두 서비스를 잇는 유일한 끈이다. marketing 이 보상 종류를 추가하면 그 값이 원장의
        // referenceType 으로 나갔다가 그대로 돌아오는데, 여기서 못 알아보면 그 종류만
        // 영원히 미확정으로 남는다 — 에러 없이, 로그도 없이.
        var schema = MAPPER.readTree(
                getClass().getClassLoader().getResourceAsStream(
                        "contracts/events/lemuel.marketing.reward_requested.schema.json"));
        var declared = schema.path("properties").path("source").path("enum");

        assertThat(declared.isArray()).isTrue();
        assertThat(declared).isNotEmpty();

        List<String> inSchema = new ArrayList<>();
        declared.forEach(node -> inSchema.add(node.asText()));

        assertThat(inSchema).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(RewardSource.values()).map(Enum::name).toList());
    }

    // ---------------------------------------------------------------- 도구

    /** 정본 샘플에서 참조 두 필드만 바꾼다 — 나머지는 발행 측이 실제로 싣는 모양 그대로 둔다. */
    private static String sampleWith(String referenceType, String referenceId) {
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                    MAPPER.readTree(EventContractValidator.canonicalSample(TOPIC));
            node.put("origin", "PROMOTION_REWARD");
            node.put("referenceType", referenceType);
            node.put("referenceId", referenceId);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConsumerRecord<String, String> record(String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
        record.headers().add("event_id", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
