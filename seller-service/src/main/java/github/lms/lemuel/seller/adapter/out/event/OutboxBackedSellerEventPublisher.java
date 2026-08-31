package github.lms.lemuel.seller.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.seller.application.port.out.PublishSellerEventPort;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 셀러의 두 요청을 outbox 에 적재한다.
 *
 * <p>브로커로 직접 쏘지 않는 이유는 원장과 요청이 <b>같은 트랜잭션에서 함께 커밋</b>되어야
 * 하기 때문이다. 승인은 났는데 발행이 유실되면 신청서는 영원히 "등록 처리 중" 에 머물고,
 * 반대로 발행은 됐는데 승인이 롤백되면 카탈로그에 아무도 승인하지 않은 상품이 실린다.
 */
@Component
public class OutboxBackedSellerEventPublisher implements PublishSellerEventPort {

    /**
     * 토픽명은 여기서 파생된다 — {@code lemuel.seller.product_approved} 와
     * {@code lemuel.seller.shipment_registered} (둘 다 카탈로그 등재명).
     */
    private static final String AGGREGATE_TYPE = "Seller";

    private final SaveOutboxEventPort outbox;
    private final ObjectMapper mapper;
    private final TraceContextCapture trace;

    /**
     * 매퍼는 반드시 {@code outboxObjectMapper} 다.
     *
     * <p>{@code new ObjectMapper()} 를 쓰면 가격이 {@code 1.29E+4} 같은 지수 표기로 나가고,
     * 수신 측이 그걸 다시 읽어 카탈로그에 <b>다른 가격</b>으로 싣는다. 공용 매퍼에 금액
     * plain string 설정과 JavaTimeModule 이 들어 있다.
     */
    public OutboxBackedSellerEventPublisher(SaveOutboxEventPort outbox,
                                            @Qualifier("outboxObjectMapper") ObjectMapper mapper,
                                            TraceContextCapture trace) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.trace = trace;
    }

    /**
     * 승인된 신청서의 <b>내용 전부</b>를 싣는다. 신청서 번호만 보내고 저쪽이 되물어 오게 하면
     * 서비스 간 동기 호출이 생기고, 그 순간 이 서비스가 죽으면 카탈로그 등록도 같이 멈춘다.
     */
    @Override
    public void productApproved(ProductSubmission submission) {
        ProductContent content = submission.content();
        Map<String, Object> payload = new LinkedHashMap<>();
        // 지역변수 이름이 카탈로그의 orderingKey(submissionId)와 대조된다 — kafka-publisher-gate 가
        // 이 이름을 읽는다. 표현식을 인라인하면 힌트가 사라진다.
        long submissionId = submission.requireSubmissionId();
        payload.put("submissionId", submissionId);
        payload.put("sellerId", submission.sellerId());
        payload.put("submissionType", submission.type().name());
        payload.put("baseProductId", submission.baseProductId());
        payload.put("name", content.name());
        payload.put("description", content.description());
        payload.put("price", content.price());
        payload.put("stock", content.stock());
        payload.put("category", content.category());
        payload.put("imageUrl", content.imageUrl());
        payload.put("displayVisible", content.displayVisible());
        try {
            outbox.save(OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(submissionId),
                    "SellerProductApproved", mapper.writeValueAsString(payload),
                    trace.captureCurrentTraceParent()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize SellerProductApproved payload", exception);
        }
    }

    /**
     * 키가 주문번호인 것이 중요하다. 셀러 번호로 묶으면 대형 셀러의 출고 요청이 한 파티션에
     * 줄을 서서, 앞의 하나가 막히면 그 셀러의 다른 주문이 전부 밀린다. 주문끼리는 순서 의존이
     * 없고, 중복은 수신 측의 {@code processed_events} 와 주문 상태가 함께 막는다.
     */
    @Override
    public void shipmentRegistered(long orderId, long sellerId, String carrier, String trackingNumber) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("sellerId", sellerId);
        payload.put("carrier", carrier);
        payload.put("trackingNumber", trackingNumber);
        // 공통 헬퍼로 묶지 않은 이유가 있다. eventType 을 파라미터로 넘기면 kafka-publisher-gate 가
        // 호출부에서 토픽을 계산하지 못해 "미해석" 으로 세고, 이 두 토픽은 게이트의 사각지대가 된다.
        // 중복 여섯 줄이 그 사각지대보다 싸다.
        try {
            outbox.save(OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(orderId),
                    "SellerShipmentRegistered", mapper.writeValueAsString(payload),
                    trace.captureCurrentTraceParent()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize SellerShipmentRegistered payload", exception);
        }
    }
}
