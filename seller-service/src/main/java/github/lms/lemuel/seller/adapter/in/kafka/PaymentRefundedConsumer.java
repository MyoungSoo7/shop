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
import java.util.UUID;

/**
 * {@code lemuel.payment.refunded} — 정산액에서 빠지는 쪽이자, 출고하면 안 되는 주문의 표시.
 *
 * <p>계약상 금액 필드가 <b>둘 다 선택</b>이다({@code refundAmount} 건별, {@code refundedTotal}
 * 누계). 둘 다 없으면 이 이벤트로부터 "얼마가 환불됐는지" 를 알 방법이 없다. 그때 0 으로 채우지
 * 않고 예외를 던져 DLT 로 보내는 것은 의도다 — 0 을 넣으면 "환불 없음" 이라는 틀린 사실이
 * 화면에 뜨고, 셀러는 이미 환불된 주문을 포장해 내보낸다. DLT 는 최소한 사람이 본다.
 *
 * <p>{@code refundKey} 는 {@code refundId} 가 있으면 그것을, 없으면 event_id 를 쓴다. 부분환불이
 * 여러 번 오는 경우 결제당 여러 행이 쌓여야 하므로 결제 ID 만으로는 키가 될 수 없다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentRefundedConsumer extends IdempotentEventConsumer {

    private static final String GROUP = "lemuel-seller";

    private final RecordCommerceUseCase recordCommerce;

    public PaymentRefundedConsumer(ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   RecordCommerceUseCase recordCommerce) {
        super(processedEventRepository, objectMapper);
        this.recordCommerce = recordCommerce;
    }

    @KafkaListener(topics = "${app.kafka.topic.payment-refunded:lemuel.payment.refunded}", groupId = GROUP)
    @Transactional
    public void onPaymentRefunded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected String eventType() {
        return "lemuel.payment.refunded";
    }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long paymentId = requiredLong(payload, "paymentId", eventId);
        long orderId = requiredLong(payload, "orderId", eventId);

        BigDecimal refundAmount = Payloads.decimalOrNull(payload, "refundAmount");
        BigDecimal refundedTotal = Payloads.decimalOrNull(payload, "refundedTotal");
        if (refundAmount == null && refundedTotal == null) {
            throw new IllegalArgumentException(
                    "환불 금액이 없다 (refundAmount·refundedTotal 둘 다 누락), paymentId=" + paymentId
                            + ", eventId=" + eventId);
        }

        String refundKey = Payloads.text(payload, "refundId");
        if (refundKey == null) {
            refundKey = eventId.toString();
        }

        recordCommerce.refunded(new RecordCommerceUseCase.SaleRefunded(
                paymentId,
                refundKey,
                orderId,
                refundAmount == null ? BigDecimal.ZERO : refundAmount,
                refundedTotal));
    }
}
