package github.lms.lemuel.product.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.product.application.port.out.PublishProductEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * product 도메인 이벤트를 outbox_events 로 영속시키는 어댑터 (Transactional Outbox).
 *
 * <p>상품 변경 트랜잭션 안에서 호출되면 상품 변경과 outbox 레코드가 한 커밋으로 원자화된다.
 * 실제 발행은 OutboxPublisherScheduler → KafkaOutboxPublisher 가 담당하며,
 * 토픽은 컨벤션상 {@code lemuel.product.changed} 로 라우팅된다(aggregate=Product, eventType=ProductChanged).
 */
@Component
public class OutboxBackedProductEventPublisher implements PublishProductEventPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxBackedProductEventPublisher.class);
    private static final String AGGREGATE_TYPE = "Product";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedProductEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                             @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                             TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void publishProductChanged(Long productId, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("name", name);
        writeOutbox(productId, "ProductChanged", payload);
    }

    private void writeOutbox(Long productId, String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        String traceParent = traceContextCapture.captureCurrentTraceParent();
        OutboxEvent event = OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(productId), eventType, json, traceParent);
        saveOutboxEventPort.save(event);
        log.debug("Outbox write: type={}, aggregateId={}", eventType, productId);
    }
}
