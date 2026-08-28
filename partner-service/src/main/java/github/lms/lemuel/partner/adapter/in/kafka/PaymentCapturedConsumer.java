package github.lms.lemuel.partner.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.partner.application.port.in.RecordSalesUseCase;
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
 * {@code lemuel.payment.captured} — 이 서비스가 받는 이벤트 중 <b>유일하게 매출의 근거가 되는</b> 것.
 *
 * <p>계약상 필수는 {@code paymentId, orderId, amount} 뿐이다. {@code sellerId} 는 선택이라
 * null 로 들어올 수 있고, 그러면 그 결제는 어느 파트너 화면에도 뜨지 않는다 — 조회가 전부
 * {@code seller_id = :sellerId} 로 걸리기 때문이다. 그래도 행은 넣는다. 넣지 않으면 나중에
 * 셀러가 채워진 재발행이 와도 붙일 곳이 없고, 넣어 두면 upsert 로 되살아난다.
 *
 * <p><b>{@code capturedAt} 이 없을 때.</b> 이것도 선택 필드다. 없으면 수신 시각으로 채우고
 * {@code captured_at_estimated = true} 를 세운다. 금액은 정확하고 날짜만 흔들리는데, 하필
 * 자정 언저리면 매출이 하루 옆으로 간다. 그 사실을 행에 남겨 두어야 대시보드가
 * "추정 포함" 을 표시할 수 있다 — 표시가 없으면 파트너는 틀린 날짜를 정확한 값으로 읽는다.
 *
 * <p>{@link Clock} 을 주입받는 이유는 {@link IdempotentEventConsumer#handle} 이
 * {@code ConsumerRecord} 를 못 보기 때문이다. 레코드 타임스탬프가 더 정확하지만 골격이
 * 넘겨주지 않으므로, 골격을 바꾸는 대신(다른 서비스 6개가 같이 걸린다) 시계를 쓴다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentCapturedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-partner";

    private final RecordSalesUseCase recordSales;
    private final Clock clock;

    public PaymentCapturedConsumer(ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   RecordSalesUseCase recordSales,
                                   Clock clock) {
        super(processedEventRepository, objectMapper);
        this.recordSales = recordSales;
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
            log.warn("capturedAt 없는 결제 {} — 수신 시각으로 대체하고 추정으로 표시한다. eventId={}",
                    paymentId, eventId);
        }

        Long sellerId = Payloads.longOrNull(payload, "sellerId");
        if (sellerId == null) {
            log.warn("sellerId 없는 결제 {} — 어떤 파트너 화면에도 집계되지 않는다. eventId={}",
                    paymentId, eventId);
        }

        recordSales.captured(new RecordSalesUseCase.SaleCaptured(
                paymentId,
                orderId,
                sellerId,
                amount,
                Payloads.text(payload, "sellerTier"),
                Payloads.text(payload, "settlementCycle"),
                Payloads.text(payload, "paymentMethod"),
                capturedAt,
                estimated));
    }
}
