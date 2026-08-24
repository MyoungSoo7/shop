package github.lms.lemuel.payment.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payment 도메인의 이벤트를 outbox_events 테이블로 영속시키는 어댑터.
 *
 * <p>도메인 서비스의 @Transactional 안에서 호출되면 비즈니스 변경과
 * outbox 레코드가 같은 커밋으로 원자화된다 — Transactional Outbox 패턴.
 *
 * <p>실제 외부 발행은 {@code OutboxPublisherScheduler} 가 담당한다.
 */
@Component
public class OutboxBackedEventPublisher implements PublishEventPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxBackedEventPublisher.class);
    private static final String AGGREGATE_TYPE = "Payment";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                      @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                      TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void publishPaymentCreated(Long paymentId, Long orderId) {
        writeOutbox(paymentId, "PaymentCreated", Map.of(
                "paymentId", paymentId,
                "orderId", orderId
        ));
    }

    @Override
    public void publishPaymentAuthorized(Long paymentId) {
        writeOutbox(paymentId, "PaymentAuthorized", Map.of(
                "paymentId", paymentId
        ));
    }

    @Override
    public void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount,
                                       java.time.LocalDateTime capturedAt,
                                       String paymentMethod, String pgTransactionId,
                                       github.lms.lemuel.payment.application.port.out.SellerSettlementMeta sellerMeta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", paymentId);
        payload.put("orderId", orderId);
        payload.put("amount", amount.toPlainString());
        // Phase 2 — 로컬 payment_view 프로젝션 적재에 필요 (정산 대상일 필터)
        if (capturedAt != null) payload.put("capturedAt", capturedAt.toString());
        // Phase 3b-4 — 프로젝션 확장 필드 (ES/QueryDSL 컷오버용)
        if (paymentMethod != null) payload.put("paymentMethod", paymentMethod);
        if (pgTransactionId != null) payload.put("pgTransactionId", pgTransactionId);
        // Event-Carried State Transfer (ADR 0020 Phase 1) — 셀러 메타 동봉 (미해석/미할당 시 생략)
        if (sellerMeta != null) {
            if (sellerMeta.sellerId() != null) payload.put("sellerId", sellerMeta.sellerId());
            if (sellerMeta.sellerTier() != null) payload.put("sellerTier", sellerMeta.sellerTier());
            if (sellerMeta.settlementCycle() != null) payload.put("settlementCycle", sellerMeta.settlementCycle());
        }
        writeOutbox(paymentId, "PaymentCaptured", payload);
    }

    @Override
    public void publishPaymentRefunded(Long paymentId, Long orderId, BigDecimal refundedAmount,
                                       BigDecimal refundAmount, Long refundId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", paymentId);
        payload.put("orderId", orderId);
        // 누적 환불액 — 프로젝션 뷰(settlement_payment_view) 갱신용
        if (refundedAmount != null) payload.put("refundedAmount", refundedAmount.toPlainString());
        // 건별 환불액(delta)·환불 ID — 역정산(netAmount 재계산·원장 역분개)용
        if (refundAmount != null) payload.put("refundAmount", refundAmount.toPlainString());
        if (refundId != null) payload.put("refundId", refundId);
        writeOutbox(paymentId, "PaymentRefunded", payload);
    }

    private void writeOutbox(Long paymentId, String eventType, Map<String, Object> payload) {
        // LinkedHashMap 으로 감싸 직렬화 순서 결정적 보장
        Map<String, Object> ordered = new LinkedHashMap<>(payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 즉시 치명적 — 이벤트 손실보다 예외로 커밋 롤백이 안전
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        // 도메인 트랜잭션 시점의 W3C trace context 캡처 → outbox 영속화 → Kafka 헤더로 복원
        String traceParent = traceContextCapture.captureCurrentTraceParent();
        OutboxEvent event = OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(paymentId),
                eventType,
                json,
                traceParent
        );
        saveOutboxEventPort.save(event);
        log.debug("Outbox write: type={}, aggregateId={}", eventType, paymentId);
    }
}
