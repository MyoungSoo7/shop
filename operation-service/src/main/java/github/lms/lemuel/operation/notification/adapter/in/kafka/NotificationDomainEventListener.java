package github.lms.lemuel.operation.notification.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.in.DispatchNotificationUseCase;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 인바운드 Kafka 어댑터. 도메인 이벤트를 {@link Notification} 으로 매핑해 팬아웃한다.
 *
 * <h2>★ 컨슈머 그룹이 {@code lemuel-operation} 이 아닌 이유</h2>
 * 두 가지가 겹친다.
 * <ol>
 *   <li><b>겹치는 토픽</b>: 옆 슬라이스의 {@code DomainEventSignalConsumer} 도
 *       {@code lemuel.payment.captured} 를 구독한다. 같은 그룹으로 두면 카프카가 둘을 한 그룹으로 보고
 *       파티션을 <b>나눠</b> 준다 — 신호 컨슈머가 가져간 파티션의 결제 이벤트는 알림으로 오지 않고,
 *       오프셋까지 공유되어 조용히 유실된다. 같은 토픽을 각자 처리해야 하는 팬아웃이므로 그룹은 달라야 한다.</li>
 *   <li><b>오프셋 승계</b>: 이 그룹 이름은 이관 전 폴리글랏 notification-service 가 쓰던 것 그대로다.
 *       이름을 바꾸면 새 그룹이 되고, 커밋된 오프셋을 잃는다 — 보존기간 안의 결제 이벤트를 전량 재처리해
 *       <b>실제 수신자에게 지난 알림을 대량 재발송</b>하거나(earliest), 반대로 전환 중 이벤트를 건너뛴다(latest).
 *       이름을 유지하면 컨테이너 교체가 곧 무결점 인계다.</li>
 * </ol>
 * 그래서 저장소 관례({@code lemuel-<모듈>})에서 의도적으로 벗어난다. guard 의 KAFKA-GROUP-OWNER 는
 * 모듈 yml 의 기본 group-id 를 보므로 이 리스너 수준의 그룹과 충돌하지 않는다.
 *
 * <h2>멱등을 processed_events 로 하지 않는 이유</h2>
 * 이 슬라이스는 DB 에 아무것도 쓰지 않는다. 멱등의 목적이 회계 정합이 아니라 "같은 메일을 두 번
 * 보내지 않는 것"이고, 고volume 결제 이벤트마다 멱등 행을 쌓으면 테이블이 무한 팽창한다
 * (옆 신호 컨슈머가 멱등 행을 안 쌓는 것과 같은 판단). 대신 인메모리 TTL dedupe 를 쓴다 —
 * 재시작 시 창이 사라지므로 <b>중복 발송 가능</b>이라는 한계를 그대로 안고 간다(docs 에 기록).
 *
 * <h2>실패를 삼키지 않는다</h2>
 * 예외를 잡지 않는다. 과거 이 어댑터는 모든 것을 {@code catch (Exception)} 으로 감쌌는데, 복원력처럼
 * 보였지만 실제로는 컨테이너가 모든 독성 메시지를 "처리됨"으로 보게 만들었다 — 오프셋이 커밋되고
 * 메시지가 사라졌다. 재시도·격리는 shared-common 의 {@code KafkaConsumerErrorHandlingConfig} 몫이고,
 * 그 일을 하려면 예외가 실제로 이 메서드를 빠져나가야 한다.
 *
 * <p>이는 던져진 예외뿐 아니라 <b>dispatch 결과</b>에도 적용된다. 디스패처는 전건 실패를 예외가 아니라
 * 모든 채널의 {@link ChannelResult.Failure} 로 보고하므로, 그 상태를
 * {@link NotificationDispatchFailedException} 으로 바꿔 던진다. 아니면 아무에게도 닿지 않은 알림이
 * 오프셋만 커밋하고 사라진다.
 *
 * <p>ack 는 성공 경로에서만 한다 — ack-mode 가 {@code MANUAL_IMMEDIATE}(operation-service yml)라
 * ack 하지 않고 예외를 던져야 에러 핸들러가 재시도/DLT 를 수행한다.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
public class NotificationDomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDomainEventListener.class);

    /** 이관 전 폴리글랏 서비스의 그룹 — 오프셋 승계를 위해 이름을 유지한다(클래스 javadoc 참조). */
    static final String GROUP = "notification-service";

    /**
     * Outbox 발행자는 유일 이벤트 UUID 를 {@code event_id} <b>헤더</b>에만 싣는다(페이로드에 eventId
     * 필드가 없고, kafka 키는 aggregateId 라 같은 애그리거트의 여러 이벤트가 공유한다 —
     * 예컨대 한 paymentId 의 captured 다음 refunded. 키로 dedupe 하면 두 번째 이벤트가 통째로 사라진다).
     */
    private static final String EVENT_ID_HEADER = "event_id";

    private final DispatchNotificationUseCase dispatcher;
    private final ObjectMapper objectMapper;

    public NotificationDomainEventListener(DispatchNotificationUseCase dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                    // 이 저장소에 프로듀서가 있는 토픽만 구독한다.
                    // `lemuel.payment.confirmed`·`lemuel.settlement.confirmed`·`lemuel.investment.executed`
                    // 는 구독하지 않는다 — 발행 주체가 이 저장소에 없어 영원히 비는 구독이 된다.
                    // (분류표는 NotificationTemplate 에 남겨 둔다 — 브로커에 남은 과거 레코드나
                    //  DLT replay 가 여전히 이 토픽명으로 디코딩돼야 한다.)
                    "lemuel.payment.captured",    // 실 결제 이벤트
                    "lemuel.payment.refunded",
            },
            groupId = GROUP)
    public void onEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String topic = record.topic();
        String key = record.key();

        // 파싱 실패를 빈 맵으로 조용히 격하하지도, 스킵하지도 않는다: 계약 토픽의 파싱 불가
        // 페이로드는 계약 드리프트를 뜻한다. 던져도 폴백 수신자에게 엉뚱한 GENERIC 알림을 만들지
        // 않으며(스킵의 원래 이유), 레코드는 재시도 없이 <topic>.DLT 로 격리된다.
        Map<String, Object> fields = parsePayload(record.value(), topic, key);

        String eventId = resolveEventId(record, fields, key);
        Notification notification = NotificationTemplate.fromEvent(topic, fields, eventId);

        DispatchResult result = dispatcher.dispatch(notification);
        log.info("kafka event topic={} eventId={} deduped={} allSucceeded={}",
                topic, eventId, result.deduped(), result.allSucceeded());

        // 활성 채널이 전부 실패했다면 이 알림은 어디에도 도달하지 않았다. 여기서 그냥 ack 하면
        // 오프셋이 커밋되고 메시지는 사라진다.
        //
        // 세 가지 비대칭이 의도된 것이다:
        //  - 부분 성공(anySucceeded)은 던지지 않는다. 이미 도달한 채널이 있는데 DLT 로 보내면
        //    replay 시 그 채널로 중복 발송된다. 실패 채널은 디스패처가 warn 으로 남긴다.
        //  - 중복 스킵(deduped)은 실패가 아니다 — 앞선 배달이 이미 처리했다.
        //  - results 가 비어 있는 경우는 "활성 채널 0개"라는 배포 설정 오류지 메시지 문제가 아니다.
        //    스트림 전량을 DLT 로 밀어 넣는 대신 디스패처의 warn + 설정 검증에 맡긴다.
        if (!result.deduped() && !result.results().isEmpty() && !result.anySucceeded()) {
            throw new NotificationDispatchFailedException(topic, eventId, failuresOf(result));
        }

        ack.acknowledge();
    }

    private Map<String, Object> parsePayload(String payload, String topic, String key) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            return parsed;
        } catch (Exception parseError) {
            log.warn("unparseable event payload — routing to DLT (contract drift?) topic={} key={} cause={}",
                    topic, key, parseError.toString());
            throw new UnparseableEventPayloadException(topic, key, parseError);
        }
    }

    /** 헤더 우선. 페이로드 필드와 키는 비-Outbox 프로듀서(Go 웹훅)를 위한 폴백으로 남긴다. */
    private static String resolveEventId(ConsumerRecord<String, String> record, Map<String, Object> fields, String key) {
        var header = record.headers().lastHeader(EVENT_ID_HEADER);
        if (header != null && header.value() != null) {
            String value = new String(header.value(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        Object fromPayload = fields.get("eventId") != null ? fields.get("eventId") : fields.get("id");
        if (fromPayload != null) {
            return fromPayload.toString();
        }
        return key;
    }

    private static List<ChannelResult.Failure> failuresOf(DispatchResult result) {
        return result.results().stream()
                .filter(ChannelResult.Failure.class::isInstance)
                .map(ChannelResult.Failure.class::cast)
                .toList();
    }
}
