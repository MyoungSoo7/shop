package github.lms.lemuel.point.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 포인트 도메인 이벤트를 {@code outbox_events} 로 영속시키는 어댑터.
 *
 * <p>도메인 서비스의 {@code @Transactional} 안에서 호출되므로 잔고 변경과 이벤트가 같은 커밋으로
 * 원자화된다(Transactional Outbox). 실제 발행은 폴러가 맡는다.
 *
 * <p>토픽명은 Outbox 규약에서 자동 도출된다 — {@code aggregateType="Point"} +
 * {@code eventType="PointCharged"} → {@code lemuel.point.charged}.
 *
 * <p>금액은 {@code toPlainString()} 문자열로 싣는다. JSON number 로 실으면 소비 측 파서가
 * double 로 받아 정밀도를 잃는다(이 저장소의 모든 금액 계약이 같은 규약).
 */
@Component
public class OutboxBackedPointEventPublisher implements PublishPointEventPort {

    private static final String AGGREGATE_TYPE = "Point";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedPointEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                           @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                           TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void pointCharged(PointAccount account, PointLot lot, String chargeReference) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", account.getUserId());
        payload.put("accountId", account.getId());
        payload.put("lotId", lot.getId());
        payload.put("amount", plain(lot.getOriginalAmount()));
        payload.put("chargeReference", chargeReference);
        payload.put("occurredAt", lot.getGrantedAt().toString());
        writeOutbox(account, "PointCharged", payload);
    }

    @Override
    public void pointGranted(PointAccount account, PointLot lot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", account.getUserId());
        payload.put("accountId", account.getId());
        payload.put("lotId", lot.getId());
        payload.put("amount", plain(lot.getOriginalAmount()));
        payload.put("origin", lot.getOrigin().name());
        payload.put("referenceType", lot.getReferenceType());
        payload.put("referenceId", lot.getReferenceId());
        payload.put("expiresAt", lot.getExpiresAt() == null ? null : lot.getExpiresAt().toString());
        payload.put("occurredAt", lot.getGrantedAt().toString());
        writeOutbox(account, "PointGranted", payload);
    }

    @Override
    public void pointUsed(PointAccount account, PointEntry entry) {
        writeOutbox(account, "PointUsed", ledgerPayload(account, entry));
    }

    @Override
    public void pointRestored(PointAccount account, PointEntry entry) {
        writeOutbox(account, "PointRestored", ledgerPayload(account, entry));
    }

    @Override
    public void pointRevoked(PointAccount account, PointEntry entry) {
        writeOutbox(account, "PointRevoked", ledgerPayload(account, entry));
    }

    @Override
    public void pointExpired(PointAccount account, PointLot lot, BigDecimal forfeitedAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", account.getUserId());
        payload.put("accountId", account.getId());
        payload.put("lotId", lot.getId());
        payload.put("amount", plain(forfeitedAmount));
        payload.put("origin", lot.getOrigin().name());
        payload.put("occurredAt", java.time.OffsetDateTime.now().toString());
        writeOutbox(account, "PointExpired", payload);
    }

    private Map<String, Object> ledgerPayload(PointAccount account, PointEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", account.getUserId());
        payload.put("accountId", account.getId());
        payload.put("entryId", entry.getId());
        payload.put("amount", plain(entry.getAmount()));
        payload.put("referenceType", entry.getReferenceType());
        payload.put("referenceId", entry.getReferenceId());
        payload.put("sequence", entry.getSequence());
        payload.put("lots", lotBreakdown(entry.getAllocations()));
        payload.put("occurredAt", entry.getCreatedAt().toString());
        return payload;
    }

    private List<Map<String, Object>> lotBreakdown(List<PointLotConsumption> allocations) {
        return allocations.stream()
                .map(allocation -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("lotId", allocation.lotId());
                    item.put("amount", plain(allocation.amount()));
                    return (Map<String, Object>) item;
                })
                .toList();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void writeOutbox(PointAccount account, String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류다. 이벤트를 잃느니
            // 예외로 커밋을 되돌리는 편이 안전하다(payment 발행기와 같은 판단).
            throw new IllegalStateException("Failed to serialize point outbox payload for " + eventType, exception);
        }
        OutboxEvent event = OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                eventType,
                json,
                traceContextCapture.captureCurrentTraceParent());
        saveOutboxEventPort.save(event);
    }
}
