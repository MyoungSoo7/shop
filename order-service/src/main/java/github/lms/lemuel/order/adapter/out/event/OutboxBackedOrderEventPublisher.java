package github.lms.lemuel.order.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * order 도메인 이벤트를 outbox_events 로 영속시키는 어댑터 (Transactional Outbox).
 *
 * <p>주문 생성 트랜잭션 안에서 호출되면 주문 변경과 outbox 레코드가 한 커밋으로 원자화된다.
 * 실제 발행은 OutboxPublisherScheduler → KafkaOutboxPublisher 가 담당하며,
 * 토픽은 컨벤션상 {@code lemuel.order.created} 로 라우팅된다(aggregate=Order, eventType=OrderCreated).
 */
@Component
public class OutboxBackedOrderEventPublisher implements PublishOrderEventPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxBackedOrderEventPublisher.class);
    private static final String AGGREGATE_TYPE = "Order";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedOrderEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                           @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                           TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void publishOrderCreated(Long orderId, Long userId, Long productId,
                                    String status, BigDecimal amount, LocalDateTime createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        if (productId != null) payload.put("productId", productId);
        payload.put("status", status);
        payload.put("amount", amount != null ? amount.toPlainString() : "0");
        if (createdAt != null) payload.put("createdAt", createdAt.toString());
        writeOutbox(orderId, "OrderCreated", payload);
    }

    private void writeOutbox(Long orderId, String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        String traceParent = traceContextCapture.captureCurrentTraceParent();
        OutboxEvent event = OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(orderId), eventType, json, traceParent);
        saveOutboxEventPort.save(event);
        log.debug("Outbox write: type={}, aggregateId={}", eventType, orderId);
    }
}
