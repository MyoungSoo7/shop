package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.partner.application.port.in.RecordCatalogUseCase;
import github.lms.lemuel.partner.domain.SellerTier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code lemuel.seller.tier_changed} — 파트너 프로필에 뜨는 등급.
 *
 * <p>이 값은 <b>표시용</b>이다. 지난 매출을 새 등급으로 다시 계산하지 않는다 — 등급 변경은
 * 소급되지 않는다는 규칙(ADR 0031, ADR 0014 §4)이 그렇게 정해져 있고, 이 서비스가 그걸
 * 어기면 정산과 화면의 숫자가 갈린다.
 *
 * <p>{@code reason=BACKFILL} 은 과거 보정이라 <i>현재</i> 등급으로 삼기에 애매하지만, 그래도
 * 적용한다. 적용하지 않으면 보정 이후 등급 이벤트가 한동안 안 오는 셀러의 화면이 영영 옛
 * 등급에 머문다. 대신 로그를 남겨 나중에 구분할 수 있게 한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SellerTierChangedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";
    private static final String BACKFILL = "BACKFILL";

    private final RecordCatalogUseCase recordCatalog;

    public SellerTierChangedConsumer(ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper,
                                     RecordCatalogUseCase recordCatalog) {
        super(processedEventRepository, objectMapper);
        this.recordCatalog = recordCatalog;
    }

    @KafkaListener(topics = "${app.kafka.topic.seller-tier-changed:lemuel.seller.tier_changed}",
            groupId = GROUP)
    @Transactional
    public void onSellerTierChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.seller.tier_changed";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long sellerId = requiredLong(payload, "sellerId", eventId);
        String reason = requiredText(payload, "reason", eventId);

        recordCatalog.sellerTierChanged(new RecordCatalogUseCase.SellerTierChanged(
                sellerId,
                SellerTier.valueOf(requiredText(payload, "newTier", eventId)),
                reason,
                LocalDate.parse(requiredText(payload, "effectiveFrom", eventId)),
                OffsetDateTime.parse(requiredText(payload, "occurredAt", eventId)),
                BACKFILL.equals(reason)));
    }
}
