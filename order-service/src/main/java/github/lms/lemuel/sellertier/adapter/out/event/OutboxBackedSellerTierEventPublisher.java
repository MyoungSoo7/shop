package github.lms.lemuel.sellertier.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierChangeReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 등급 변경 이벤트를 outbox_events 로 영속시키는 어댑터 (Transactional Outbox).
 *
 * <p>등급 저장 트랜잭션 안에서 호출돼 등급 변경과 outbox 레코드가 한 커밋으로 원자화된다 —
 * 직접 Kafka send 를 하면 등급은 바뀌었는데 통지가 못 나가거나 그 반대가 생긴다.
 *
 * <p>근거 금액은 {@code toPlainString()} 문자열로 싣는다. 숫자로 실으면 소비측 파서에 따라 정밀도가
 * 깎이는데, 이 값이 곧 판정 근거라 조용히 틀리면 나중에 "왜 이때 올랐나"를 설명할 수 없다.
 */
@Component
public class OutboxBackedSellerTierEventPublisher implements PublishSellerTierEventPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxBackedSellerTierEventPublisher.class);
    private static final String AGGREGATE_TYPE = "Seller";
    private static final String EVENT_TYPE = "SellerTierChanged";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedSellerTierEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                                @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                                TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void publishTierChanged(Long sellerId, SellerTierGrade prevTier, SellerTierGrade newTier,
                                   TierChangeReason reason, LocalDate effectiveFrom, BigDecimal basisAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sellerId", sellerId);
        payload.put("prevTier", prevTier == null ? null : prevTier.name());
        payload.put("newTier", newTier.name());
        payload.put("reason", reason.name());
        payload.put("effectiveFrom", effectiveFrom.toString());
        // 근거 금액이 없으면(관리자 지정) null 을 싣지 않고 필드 자체를 생략한다 — 금액 필드는
        // JSON string 만 허용이라(DATA-STANDARD N5) null 유니온을 두면 계약이 성립하지 않는다.
        // required 가 아니므로 생략이 곧 "근거 없음"이고, 소비측은 이 필드를 읽지 않는다.
        if (basisAmount != null) {
            payload.put("basisAmount", basisAmount.toPlainString());
        }
        payload.put("occurredAt", LocalDateTime.now().toString());

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류(프로그래밍 오류 가드)라 generic 유지.
            throw new IllegalStateException("Failed to serialize outbox payload for " + EVENT_TYPE, e);
        }
        saveOutboxEventPort.save(OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(sellerId),
                EVENT_TYPE, json, traceContextCapture.captureCurrentTraceParent()));
        log.debug("Outbox write: type={}, sellerId={}, {} to {}", EVENT_TYPE, sellerId, prevTier, newTier);
    }
}
