package github.lms.lemuel.user.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * user 도메인 이벤트를 outbox_events 로 영속시키는 어댑터 (Transactional Outbox).
 *
 * <p>도메인 트랜잭션 안에서 호출되면 회원 변경과 outbox 레코드가 한 커밋으로 원자화된다.
 * 실제 Kafka 발행은 {@code OutboxPublisherScheduler} → {@code KafkaOutboxPublisher} 가 담당하며,
 * 토픽은 컨벤션상 {@code lemuel.user.membership_changed} 로 라우팅된다
 * (aggregate=User, eventType=UserMembershipChanged).
 */
@Component
public class OutboxBackedUserEventPublisher implements PublishUserEventPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxBackedUserEventPublisher.class);
    private static final String AGGREGATE_TYPE = "User";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedUserEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                          @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                          TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void publishMembershipChanged(Long userId, String role, String membershipStatus, boolean active) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("role", role);
        payload.put("membershipStatus", membershipStatus);
        payload.put("active", active);
        writeOutbox(userId, "UserMembershipChanged", payload);
    }

    @Override
    public void publishUserRegistered(Long userId, String email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("email", email);
        writeOutbox(userId, "UserRegistered", payload);
    }

    private void writeOutbox(Long userId, String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        String traceParent = traceContextCapture.captureCurrentTraceParent();
        OutboxEvent event = OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(userId),
                eventType,
                json,
                traceParent
        );
        saveOutboxEventPort.save(event);
        log.debug("Outbox write: type={}, aggregateId={}", eventType, userId);
    }
}
