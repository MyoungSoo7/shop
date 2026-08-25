package github.lms.lemuel.operation.dashboard.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.operation.dashboard.application.port.in.RecordDailyMetricUseCase;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 비즈니스 도메인 이벤트 → "오늘 한눈에" 일별 집계.
 *
 * <h2>왜 컨슈머 그룹이 따로인가 — 이게 제일 중요하다</h2>
 * 이 서비스에는 이미 {@code DomainEventSignalConsumer} 가 그룹 {@code lemuel-operation} 으로
 * {@code order.created}·{@code payment.captured} 를 구독 중이다. 같은 그룹으로 리스너를 하나 더
 * 붙이면 카프카는 둘을 <b>한 그룹의 두 컨슈머</b>로 보고 파티션을 나눠 준다. 그런데
 * {@code lemuel.order.created} 는 파티션이 1개라 둘 중 하나만 레코드를 받고 나머지는
 * 아무것도 못 받는다 — 장애 감지가 조용히 눈이 먼다. 에러도 로그도 없다.
 * 그래서 반드시 별도 그룹이어야 한다.
 *
 * <h2>왜 옆 컨슈머와 반대로 멱등을 쓰는가</h2>
 * 신호 컨슈머는 멱등을 <b>일부러</b> 쓰지 않는다. 5분 버킷의 실패율은 상대 지표라 중복 몇 건에
 * 안 흔들리고, 고volume 이벤트마다 멱등 행을 쌓으면 테이블이 무한 팽창하기 때문이다. 그 판단은
 * 그 자리에서 옳다. 여기는 다르다 — "오늘 매출 45만원"은 사람이 사실로 읽는 절대값이고,
 * at-least-once 재전송 한 번이면 그대로 부풀어 오르며 아무도 눈치채지 못한다.
 * 옆에 있는 코드를 따라 하면 틀리는 자리라 이 문단을 남긴다.
 *
 * <h2>실패하면 ack 하지 않는다</h2>
 * 신호 컨슈머는 적재에 실패해도 ack 하고 넘어간다(통계 한 칸 손실). 여기서 같은 선택을 하면
 * 매출 합계가 조용히 작아진다. 트랜잭션이 롤백되면 ack 도 일어나지 않아 재전달되고, 멱등이
 * 있으므로 재전달은 안전하다. 실패를 눈에 보이게 두는 쪽이 낫다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class BusinessEventDashboardConsumer extends IdempotentEventConsumer {

    /** ★ 기존 lemuel-operation 과 반드시 달라야 한다 — 클래스 주석 참조. */
    static final String GROUP = "lemuel-operation-dashboard";

    /**
     * 리스너 메서드가 받은 레코드를 {@link #handle} 로 넘기는 통로.
     *
     * <p>{@code IdempotentEventConsumer.handle} 은 payload 와 eventId 만 준다. 어느 지표인지
     * (=어느 토픽이었는지)와 사건 시각을 알려면 레코드가 필요한데, 골격의 {@code consume} 은
     * final 이라 시그니처를 넓힐 수 없다. 리스너 스레드가 레코드 하나를 끝까지 처리하는 동안만
     * 유효하며 {@code finally} 에서 반드시 지운다 — 남기면 다음 레코드가 앞 레코드의 지표로
     * 집계된다.
     */
    private static final ThreadLocal<Inbound> INBOUND = new ThreadLocal<>();

    private final RecordDailyMetricUseCase recordUseCase;

    public BusinessEventDashboardConsumer(ProcessedEventRepository processedEventRepository,
                                          ObjectMapper objectMapper,
                                          RecordDailyMetricUseCase recordUseCase) {
        super(processedEventRepository, objectMapper);
        this.recordUseCase = recordUseCase;
    }

    @KafkaListener(topics = "${app.ops.dashboard.topics.order-created:lemuel.order.created}",
            groupId = GROUP)
    @Transactional
    public void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        dispatch(DashboardMetric.ORDER_CREATED, record, ack);
    }

    @KafkaListener(topics = "${app.ops.dashboard.topics.payment-captured:lemuel.payment.captured}",
            groupId = GROUP)
    @Transactional
    public void onPaymentCaptured(ConsumerRecord<String, String> record, Acknowledgment ack) {
        dispatch(DashboardMetric.PAYMENT_CAPTURED, record, ack);
    }

    @KafkaListener(topics = "${app.ops.dashboard.topics.payment-refunded:lemuel.payment.refunded}",
            groupId = GROUP)
    @Transactional
    public void onPaymentRefunded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        dispatch(DashboardMetric.PAYMENT_REFUNDED, record, ack);
    }

    @KafkaListener(topics = "${app.ops.dashboard.topics.user-registered:lemuel.user.registered}",
            groupId = GROUP)
    @Transactional
    public void onUserRegistered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        dispatch(DashboardMetric.USER_REGISTERED, record, ack);
    }

    private void dispatch(DashboardMetric metric, ConsumerRecord<String, String> record, Acknowledgment ack) {
        INBOUND.set(new Inbound(metric, record));
        try {
            consume(record, ack);
        } finally {
            INBOUND.remove();
        }
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return inbound().metric().name();
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        Inbound inbound = inbound();
        recordUseCase.record(
                inbound.metric(),
                occurredAt(inbound.record()),
                amountOf(inbound.metric(), payload));
    }

    private static Inbound inbound() {
        Inbound inbound = INBOUND.get();
        if (inbound == null) {
            // 도달하면 골격이나 배선이 바뀐 것이다. 조용히 기본값으로 넘어가면 엉뚱한 지표에
            // 더해지므로 여기서 멈춘다.
            throw new IllegalStateException("리스너 컨텍스트 없이 handle 이 호출됐다");
        }
        return inbound;
    }

    /**
     * 사건 시각 — 날짜 칸을 정하는 값.
     *
     * <p>{@code occurred_at} 헤더(N4 봉투)가 정본이다. 도메인 트랜잭션 시점이라 아웃박스 폴링
     * 지연이나 재전송에 흔들리지 않는다. 이 봉투가 붙기 전에 발행돼 아직 남아 있는 레코드와,
     * 봉투를 안 붙이는 프로듀서를 위해 레코드 타임스탬프로 물러선다 — 그쪽은 <b>발행</b> 시각이라
     * 자정 근처에서 하루가 밀릴 수 있지만, 집계를 통째로 버리는 것보다는 낫다.
     *
     * <p>payload 의 {@code createdAt}/{@code capturedAt} 을 쓰지 않는 것은 지표마다 이름이 다르고
     * 환불·가입 이벤트에는 아예 없기 때문이다. 네 지표가 서로 다른 시계를 쓰면 같은 화면 안에서
     * 날짜 경계가 어긋난다.
     */
    private static Instant occurredAt(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("occurred_at");
        if (header != null) {
            String raw = new String(header.value(), StandardCharsets.UTF_8);
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException e) {
                // 헤더가 깨진 것뿐이므로 이벤트를 버리지 않고 발행 시각으로 넘어간다.
                return Instant.ofEpochMilli(record.timestamp());
            }
        }
        return Instant.ofEpochMilli(record.timestamp());
    }

    /**
     * 금액. 읽을 수 없으면 {@code null} 이고, 그 건은 건수만 세이며 "금액 미상"으로 표시된다.
     *
     * <p><b>계약 위반을 DLT 로 보내지 않는 이유</b>: 이 컨슈머는 통계일 뿐인데 금액 하나 때문에
     * 예외를 던지면 그 레코드가 공용 DLT 로 가고 재시도를 낭비한다. 원장이 아니라 카드 숫자다.
     * 대신 <b>모른다는 사실을 버리지 않고</b> 세어서 화면이 "일부 금액 미상"을 말하게 한다.
     */
    private static BigDecimal amountOf(DashboardMetric metric, JsonNode payload) {
        return switch (metric) {
            case ORDER_CREATED, PAYMENT_CAPTURED -> decimal(payload, "amount");
            // ★ refundedAmount(누적)가 아니라 refundAmount(이번 건 delta)다. 계약은 "둘 중 하나만
            // 있어도 유효" 이므로 delta 가 없는 이벤트가 실제로 온다. 그때 누적액을 합계에 더하면
            // 부분환불이 여러 번 겹쳐 계산돼 환불액이 실제보다 커진다. 이전 상태 없이 delta 를
            // 역산하는 것은 추측이라, 추측 대신 미상으로 남긴다.
            case PAYMENT_REFUNDED -> decimal(payload, "refundAmount");
            case USER_REGISTERED -> null;
        };
    }

    private static BigDecimal decimal(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            // 금액은 BigDecimal.toPlainString() 문자열로 온다(계약). 부동소수로 받으면 정밀도가 샌다.
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 리스너 스레드가 처리 중인 레코드와 그 지표. */
    private record Inbound(DashboardMetric metric, ConsumerRecord<String, String> record) {
    }
}
