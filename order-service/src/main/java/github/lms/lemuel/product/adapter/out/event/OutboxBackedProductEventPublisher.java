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

    /**
     * 셀러 신청서의 카탈로그 등재 회신 — {@code lemuel.product.registered}.
     *
     * <p>{@link #writeOutbox} 헬퍼를 쓰지 않고 여기서 {@code OutboxEvent.pending(...)} 을 직접 부른다.
     * eventType 을 파라미터로 넘기면 {@code kafka-publisher-gate} 가 호출부에서 토픽을 계산하지 못해
     * 이 발행이 "미해석" 으로 세어지고, 그러면 계약 카탈로그와 코드가 어긋나도 게이트가 침묵한다.
     * (기존 {@code ProductChanged} 가 그 상태다 — 새로 추가하는 것까지 사각지대에 넣지는 않는다.)
     *
     * <p>파티션 키가 {@code productId} 인 것도 카탈로그의 {@code orderingKey} 와 대조된다.
     * 신청서 번호로 묶으면 같은 상품에 대한 등재와 이후 변경이 다른 파티션으로 흩어진다.
     */
    @Override
    public void publishSellerProductRegistered(Long productId, String name, long submissionId, long sellerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("name", name);
        payload.put("submissionId", submissionId);
        payload.put("sellerId", sellerId);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ProductRegistered payload", e);
        }
        OutboxEvent event = OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(productId),
                "ProductRegistered", json, traceContextCapture.captureCurrentTraceParent());
        saveOutboxEventPort.save(event);
        log.debug("Outbox write: type=ProductRegistered, productId={}, submissionId={}", productId, submissionId);
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
