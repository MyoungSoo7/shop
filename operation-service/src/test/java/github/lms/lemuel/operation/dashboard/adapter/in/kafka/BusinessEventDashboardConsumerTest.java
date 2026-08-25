package github.lms.lemuel.operation.dashboard.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.operation.dashboard.application.port.in.RecordDailyMetricUseCase;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "오늘 한눈에" 집계 컨슈머.
 *
 * <p>페이로드는 되도록 <b>계약의 표준 샘플</b>을 그대로 쓴다. 테스트가 자기만의 JSON 을 들고
 * 있으면 계약이 바뀌어도 초록불이 유지되고, 그때부터 이 테스트는 아무것도 지키지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class BusinessEventDashboardConsumerTest {

    @Mock
    ProcessedEventRepository processedEventRepository;
    @Mock
    RecordDailyMetricUseCase recordUseCase;
    @Mock
    Acknowledgment ack;

    BusinessEventDashboardConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BusinessEventDashboardConsumer(
                processedEventRepository, new ObjectMapper(), recordUseCase);
    }

    private static final Instant OCCURRED = Instant.parse("2026-08-25T01:20:00Z");
    private static final long PUBLISHED_MS = Instant.parse("2026-08-25T02:00:00Z").toEpochMilli();

    private ConsumerRecord<String, String> record(String topic, String payload,
                                                  boolean withOccurredAt) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        if (withOccurredAt) {
            headers.add("occurred_at", OCCURRED.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new ConsumerRecord<>(topic, 0, 0L, PUBLISHED_MS, TimestampType.CREATE_TIME,
                -1, -1, "key", payload, headers, Optional.empty());
    }

    /** 계약 샘플 + 계약 검증. 샘플이 스키마를 어기면 여기서 먼저 터진다. */
    private ConsumerRecord<String, String> sampleRecord(String topic) {
        String payload = EventContractValidator.canonicalSample(topic);
        EventContractValidator.assertValid(topic, payload);
        return record(topic, payload, true);
    }

    private BigDecimal capturedAmount(DashboardMetric metric) {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(recordUseCase).record(eq(metric), eq(OCCURRED), amount.capture());
        return amount.getValue();
    }

    @Test
    @DisplayName("주문 생성: 금액을 문자열 계약대로 읽어 occurred_at 시각으로 집계한다")
    void orderCreated() {
        consumer.onOrderCreated(sampleRecord("lemuel.order.created"), ack);

        assertThat(capturedAmount(DashboardMetric.ORDER_CREATED))
                .isEqualByComparingTo("45000");
        verify(processedEventRepository).save(any(ProcessedEventJpaEntity.class));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("결제 완료: amount 를 매출로 누적한다")
    void paymentCaptured() {
        consumer.onPaymentCaptured(sampleRecord("lemuel.payment.captured"), ack);

        assertThat(capturedAmount(DashboardMetric.PAYMENT_CAPTURED))
                .isEqualByComparingTo("45000");
    }

    /**
     * 이 저장소에서 제일 헷갈리는 자리 — 환불 이벤트에는 금액 필드가 둘이다.
     * {@code refundAmount} 는 이번 건, {@code refundedAmount} 는 그 주문의 누적이다.
     * 누적값을 집계에 더하면 부분환불이 겹칠수록 환불액이 실제보다 커진다.
     */
    @Test
    @DisplayName("환불: 누적(refundedAmount) 이 아니라 이번 건(refundAmount) 을 더한다")
    void refundUsesDeltaNotCumulative() {
        consumer.onPaymentRefunded(sampleRecord("lemuel.payment.refunded"), ack);

        assertThat(capturedAmount(DashboardMetric.PAYMENT_REFUNDED))
                .isEqualByComparingTo("5000")   // ← 샘플의 refundAmount
                .isNotEqualByComparingTo("15000"); // ← refundedAmount(누적) 를 쓰면 여기서 걸린다
    }

    /**
     * 계약은 두 금액 필드 중 <b>하나만</b> 있어도 유효하다. 누적값만 온 이벤트에서 delta 를
     * 역산하려면 이전 상태를 알아야 하는데 이 컨슈머는 모른다 — 추측 대신 "미상"으로 둔다.
     */
    @Test
    @DisplayName("누적액만 온 환불은 건수만 세고 금액은 미상으로 남긴다")
    void refundWithoutDeltaIsUnknownAmount() {
        String payload = """
                {"paymentId":1001,"orderId":5001,"refundedAmount":"15000"}""";
        EventContractValidator.assertValid("lemuel.payment.refunded", payload);

        consumer.onPaymentRefunded(record("lemuel.payment.refunded", payload, true), ack);

        verify(recordUseCase).record(DashboardMetric.PAYMENT_REFUNDED, OCCURRED, null);
    }

    @Test
    @DisplayName("가입 이벤트는 금액이 없다 — 0원이 아니라 없음이다")
    void userRegisteredHasNoAmount() {
        consumer.onUserRegistered(sampleRecord("lemuel.user.registered"), ack);

        verify(recordUseCase).record(DashboardMetric.USER_REGISTERED, OCCURRED, null);
    }

    /**
     * 금액이 깨져도 이벤트를 버리지 않는다. 카드에 찍힐 숫자 하나 때문에 레코드를 DLT 로 보내면
     * 건수까지 같이 잃는다.
     */
    @Test
    @DisplayName("금액이 숫자가 아니면 건수만 세고 예외를 던지지 않는다")
    void unparseableAmountIsUnknownNotError() {
        consumer.onPaymentCaptured(
                record("lemuel.payment.captured",
                        """
                        {"paymentId":1,"orderId":2,"amount":"사만오천원"}""", true), ack);

        verify(recordUseCase).record(DashboardMetric.PAYMENT_CAPTURED, OCCURRED, null);
        verify(ack).acknowledge();
    }

    /**
     * N4 봉투가 붙기 전에 발행돼 아직 큐에 남아 있는 레코드가 있다. 그런 건 발행 시각으로라도
     * 센다 — 날짜가 자정 근처에서 밀릴 수는 있어도, 집계를 통째로 버리는 것보다 낫다.
     */
    @Test
    @DisplayName("occurred_at 헤더가 없으면 레코드 발행 시각으로 물러선다")
    void fallsBackToRecordTimestamp() {
        consumer.onOrderCreated(
                record("lemuel.order.created",
                        EventContractValidator.canonicalSample("lemuel.order.created"), false), ack);

        verify(recordUseCase).record(eq(DashboardMetric.ORDER_CREATED),
                eq(Instant.ofEpochMilli(PUBLISHED_MS)), any());
    }

    /**
     * 재전송은 정상 동작이다(at-least-once). 여기서 두 번 더하면 "오늘 매출"이 조용히 부풀고,
     * 부풀었다는 사실을 알아챌 방법이 없다.
     */
    @Test
    @DisplayName("이미 처리한 이벤트는 다시 더하지 않는다")
    void duplicateEventIsNotCountedTwice() {
        when(processedEventRepository.existsById(any())).thenReturn(true);

        consumer.onOrderCreated(sampleRecord("lemuel.order.created"), ack);

        verifyNoInteractions(recordUseCase);
        verify(processedEventRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    /**
     * 지표는 ThreadLocal 로 전달된다. 하나가 남으면 다음 레코드가 <b>앞 레코드의 지표로</b>
     * 집계돼, 주문 건수가 환불 칸에 쌓이는 식으로 조용히 틀어진다.
     */
    @Test
    @DisplayName("연달아 처리해도 앞 레코드의 지표가 뒤에 새지 않는다")
    void metricDoesNotLeakBetweenRecords() {
        consumer.onOrderCreated(sampleRecord("lemuel.order.created"), ack);
        consumer.onUserRegistered(sampleRecord("lemuel.user.registered"), ack);

        verify(recordUseCase).record(eq(DashboardMetric.ORDER_CREATED), any(), any());
        verify(recordUseCase).record(DashboardMetric.USER_REGISTERED, OCCURRED, null);
    }

    // 컨슈머 그룹이 기존 신호 컨슈머와 갈라져 있는지는 KafkaConsumerGroupIsolationTest 가
    // 리스너 전수를 훑어서 본다 — 여기서 문자열 하나를 비교하는 것보다 그쪽이 진짜 불변식이다.
}
