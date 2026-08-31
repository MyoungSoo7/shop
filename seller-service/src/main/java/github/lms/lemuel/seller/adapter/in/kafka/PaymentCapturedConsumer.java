package github.lms.lemuel.seller.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.seller.application.port.in.RecordCommerceUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * {@code lemuel.payment.captured} — 셀러에게 주문이 <b>보이기 시작하는</b> 유일한 지점.
 *
 * <p>계약상 필수는 {@code paymentId, orderId, amount} 뿐이다. {@code sellerId} 는 선택이라
 * null 로 들어올 수 있고, 그러면 그 주문은 어느 셀러 화면에도 뜨지 않는다 — 조회가 전부
 * {@code seller_id = :sellerId} 로 걸리기 때문이다. 그래도 행은 넣는다. 넣지 않으면 셀러가
 * 채워진 재발행이 와도 붙일 곳이 없고, 넣어 두면 upsert 로 되살아난다.
 *
 * <p><b>{@code capturedAt} 이 없을 때가 파트너 콘솔보다 무겁다.</b> 저쪽에서 이 날짜는 매출을
 * 어느 날에 세느냐의 문제였지만, 여기서는 셀러가 이 날짜로부터 <b>출고 기한</b>을 센다. 수신
 * 시각으로 대체하면 하루 뒤로 밀릴 수 있고, 그 사실을 행에 남기지 않으면 셀러는 지연 사실을
 * 모른 채 기한을 넘긴다. 그래서 {@code captured_at_estimated} 를 세우고 화면에도 표시한다.
 *
 * <p>{@link Clock} 을 주입받는 이유는 {@link IdempotentEventConsumer#handle} 이
 * {@code ConsumerRecord} 를 못 보기 때문이다. 레코드 타임스탬프가 더 정확하지만 골격이
 * 넘겨주지 않으므로, 골격을 바꾸는 대신(다른 서비스들이 같이 걸린다) 시계를 쓴다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentCapturedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordCommerceUseCase recordCommerce;
    private final Clock clock;

    public PaymentCapturedConsumer(ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   RecordCommerceUseCase recordCommerce,
                                   Clock clock) {
        super(processedEventRepository, objectMapper);
        this.recordCommerce = recordCommerce;
        this.clock = clock;
    }

    @KafkaListener(topics = "${app.kafka.topic.payment-captured:lemuel.payment.captured}", groupId = GROUP)
    @Transactional
    public void onPaymentCaptured(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.payment.captured";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long paymentId = requiredLong(payload, "paymentId", eventId);
        long orderId = requiredLong(payload, "orderId", eventId);
        BigDecimal amount = requiredDecimal(payload, "amount", eventId);

        LocalDateTime capturedAt = Payloads.localDateTimeOrNull(payload, "capturedAt");
        boolean estimated = capturedAt == null;
        if (estimated) {
            capturedAt = LocalDateTime.now(clock);
            log.warn("capturedAt 없는 결제 {} — 수신 시각으로 대체한다. 셀러의 출고 기한이 이 날짜에서 세어지므로 추정으로 표시한다. eventId={}",
                    paymentId, eventId);
        }

        Long sellerId = Payloads.longOrNull(payload, "sellerId");
        if (sellerId == null) {
            log.warn("sellerId 없는 결제 {} — 어느 셀러 화면에도 뜨지 않는다. eventId={}", paymentId, eventId);
        }

        recordCommerce.captured(new RecordCommerceUseCase.SaleCaptured(
                paymentId,
                orderId,
                sellerId,
                amount,
                Payloads.text(payload, "paymentMethod"),
                capturedAt,
                estimated));
    }
}
