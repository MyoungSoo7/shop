package github.lms.lemuel.giftcard.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기프트카드 도메인 이벤트를 {@code outbox_events} 로 영속시키는 어댑터.
 *
 * <p>토픽명은 Outbox 규약에서 도출된다 — {@code aggregateType="GiftCard"} +
 * {@code eventType="GiftCardRegistered"} → {@code lemuel.giftcard.registered}
 * (eventType 에서 aggregateType 접두를 떼고 camel→snake).
 *
 * <p><b>코드도 코드 해시도 페이로드에 넣지 않는다.</b> 이벤트는 Kafka 를 지나 다른 서비스 로그까지
 * 흘러가므로, 재산에 해당하는 값을 실으면 유출 경로가 그만큼 넓어진다. 카드 식별자와 뒤 4자리면
 * 회계와 추적에 충분하다.
 */
@Component
public class OutboxBackedGiftCardEventPublisher implements PublishGiftCardEventPort {

    private static final String AGGREGATE_TYPE = "GiftCard";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedGiftCardEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                              @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                              TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void giftCardRegistered(GiftCard card, GiftCardEntry entry) {
        Map<String, Object> payload = base(card);
        payload.put("entryId", entry.getId());
        payload.put("amount", plain(card.getFaceAmount()));
        payload.put("expiresAt", card.getExpiresAt().toString());
        payload.put("occurredAt", entry.getCreatedAt().toString());
        writeOutbox(card, "GiftCardRegistered", payload);
    }

    @Override
    public void giftCardUsed(GiftCard card, GiftCardEntry entry) {
        writeOutbox(card, "GiftCardUsed", ledgerPayload(card, entry));
    }

    @Override
    public void giftCardRestored(GiftCard card, GiftCardEntry entry) {
        writeOutbox(card, "GiftCardRestored", ledgerPayload(card, entry));
    }

    @Override
    public void giftCardExpired(GiftCard card, BigDecimal forfeitedAmount) {
        Map<String, Object> payload = base(card);
        payload.put("amount", plain(forfeitedAmount));
        payload.put("occurredAt", OffsetDateTime.now().toString());
        writeOutbox(card, "GiftCardExpired", payload);
    }

    private Map<String, Object> ledgerPayload(GiftCard card, GiftCardEntry entry) {
        Map<String, Object> payload = base(card);
        payload.put("entryId", entry.getId());
        payload.put("amount", plain(entry.getAmount()));
        payload.put("referenceType", entry.getReferenceType());
        payload.put("referenceId", entry.getReferenceId());
        payload.put("sequence", entry.getSequence());
        payload.put("occurredAt", entry.getCreatedAt().toString());
        return payload;
    }

    private Map<String, Object> base(GiftCard card) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("giftCardId", card.getId());
        payload.put("userId", card.getOwnerUserId());
        payload.put("codeLast4", card.getCodeLast4());
        payload.put("remainingAmount", plain(card.getRemainingAmount()));
        return payload;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void writeOutbox(GiftCard card, String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류다. 이벤트를 잃느니
            // 예외로 커밋을 되돌리는 편이 안전하다(다른 발행기와 같은 판단).
            throw new IllegalStateException(
                    "Failed to serialize gift card outbox payload for " + eventType, exception);
        }
        OutboxEvent event = OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(card.getId()),
                eventType,
                json,
                traceContextCapture.captureCurrentTraceParent());
        saveOutboxEventPort.save(event);
    }
}
