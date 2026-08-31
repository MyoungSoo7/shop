package github.lms.lemuel.product.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.product.application.port.in.RegisterSellerProductUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code lemuel.seller.product_approved} → 카탈로그 등재.
 *
 * <h2>몰에 상품이 실리는 두 번째 경로다</h2>
 * 지금까지 상품은 운영자가 백오피스에서 직접 만드는 것뿐이었다. 여기서부터는 셀러가 올린 것이
 * <b>심사를 통과했을 때만</b> 같은 카탈로그에 들어온다. 승인 판단은 seller-service 가 하고
 * 이쪽은 그 결정을 집행한다 — 여기서 다시 심사하지 않는다. 두 곳에서 판단하면 어느 쪽이
 * 진짜 승인인지 알 수 없어진다.
 *
 * <h2>실려 오지만 쓰지 않는 필드가 있다</h2>
 * {@code category}·{@code imageUrl}·{@code displayVisible} 은 등재에 반영하지 않는다. 이 카탈로그의
 * 카테고리는 자유 문자열이 아니라 {@code categoryId} 이고, 이미지는 상품 테이블이 아니라 별도
 * 이미지 테이블에 있다. 문자열을 아무 카테고리에나 갖다 붙이는 추측 매핑을 넣느니 반영하지 않는
 * 편이 낫다 — 셀러가 적은 값은 신청서에 그대로 남아 심사자가 보고, 분류와 이미지는 운영
 * 백오피스에서 붙인다. 무시했다는 사실을 로그로 남겨 "적었는데 아무 일도 안 일어났다" 가
 * 조용히 지나가지 않게 한다.
 *
 * <h2>실패</h2>
 * 상품명 중복({@code DuplicateProductNameException})은 재시도해도 풀리지 않고 DLT 로 간다.
 * 자동으로 이름을 바꿔 등재하지 않는다 — 셀러가 신청한 이름과 다른 이름으로 몰에 걸리는 것이
 * 등재가 늦는 것보다 나쁘다. 운영자가 신청서를 반려하고 사유를 적으면 셀러가 고쳐 다시 낸다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SellerProductApprovedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-order";
    private static final String EVENT_TYPE = "SellerProductApproved";
    private static final String TYPE_UPDATE = "UPDATE";

    private final RegisterSellerProductUseCase registerSellerProductUseCase;

    public SellerProductApprovedConsumer(ProcessedEventRepository processedEventRepository,
                                         ObjectMapper objectMapper,
                                         RegisterSellerProductUseCase registerSellerProductUseCase) {
        super(processedEventRepository, objectMapper);
        this.registerSellerProductUseCase = registerSellerProductUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.seller-product-approved:lemuel.seller.product_approved}",
            groupId = GROUP)
    @Transactional
    public void onSellerProductApproved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return EVENT_TYPE;
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long submissionId = requiredLong(payload, "submissionId", eventId);
        long sellerId = requiredLong(payload, "sellerId", eventId);
        String name = requiredText(payload, "name", eventId);
        BigDecimal price = requiredDecimal(payload, "price", eventId);
        int stock = (int) requiredLong(payload, "stock", eventId);

        warnIgnoredFields(payload, submissionId);

        registerSellerProductUseCase.register(new RegisterSellerProductUseCase.SellerProductApproval(
                submissionId,
                sellerId,
                targetProductId(payload, eventId, submissionId),
                name,
                optionalText(payload, "description"),
                price,
                stock));
    }

    /**
     * 신규면 null, 수정이면 대상 상품번호.
     *
     * <p>{@code baseProductId} 만 보고 판단하지 않는다. 타입이 {@code UPDATE} 인데 대상이 비어 있으면
     * 그건 <b>신규로 처리해도 되는 상황이 아니라</b> 잘못 만들어진 이벤트다 — 그대로 신규 등록하면
     * 셀러가 고치려던 상품 옆에 같은 이름의 상품이 하나 더 생기고, 상품명 중복 검사에 걸려
     * 원인이 "이름 중복" 으로 보고된다. 계약 위반으로 즉시 DLT 에 세운다.
     */
    private static Long targetProductId(JsonNode payload, UUID eventId, long submissionId) {
        String type = optionalText(payload, "submissionType");
        if (!TYPE_UPDATE.equals(type)) {
            return null;
        }
        JsonNode baseProductId = payload.get("baseProductId");
        if (baseProductId == null || baseProductId.isNull()) {
            throw new IllegalArgumentException("수정 신청인데 baseProductId 가 없다: eventId=" + eventId
                    + ", submissionId=" + submissionId);
        }
        return baseProductId.asLong();
    }

    /** 셀러가 적었지만 카탈로그에 반영되지 않는 값들 — 무시한 사실을 남긴다. */
    private void warnIgnoredFields(JsonNode payload, long submissionId) {
        String category = optionalText(payload, "category");
        String imageUrl = optionalText(payload, "imageUrl");
        if (category == null && imageUrl == null) {
            return;
        }
        log.info("셀러 신청서의 분류·이미지는 등재에 반영하지 않는다(운영 백오피스에서 지정). "
                + "submissionId={}, category={}, imageUrl={}", submissionId, category, imageUrl);
    }

    private static String optionalText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }
}
