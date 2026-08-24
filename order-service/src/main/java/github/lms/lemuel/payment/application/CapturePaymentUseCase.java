package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인→매입(capture) 유스케이스.
 *
 * <p><b>아키텍처 노트:</b> 정산 생성은 본 트랜잭션의 직접 호출이 아니라
 * outbox 이벤트({@code publishPaymentCaptured}) 를 통해 Kafka 컨슈머가 수행한다.
 * 이로써 결제 트랜잭션의 원자성이 정산 서비스 가용성과 분리된다.
 * Kafka 가 비활성({@code app.kafka.enabled=false}) 이면 ApplicationEventOutboxPublisher
 * 폴백으로 전달되고, 배치 CronJob 이 누락분을 reconcile 한다.
 */
@Service
@Transactional
public class CapturePaymentUseCase implements CapturePaymentPort {

    private static final Logger log = LoggerFactory.getLogger(CapturePaymentUseCase.class);

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;

    public CapturePaymentUseCase(LoadPaymentPort loadPaymentPort,
                                 SavePaymentPort savePaymentPort,
                                 PgClientPort pgClientPort,
                                 UpdateOrderStatusPort updateOrderStatusPort,
                                 PublishEventPort publishEventPort,
                                 github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadSellerSettlementMetaPort = loadSellerSettlementMetaPort;
    }

    @Override
    public PaymentDomain capturePayment(Long paymentId) {
        PaymentDomain paymentDomain = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        pgClientPort.capture(paymentDomain.getPgTransactionId(), paymentDomain.getAmount());
        paymentDomain.capture();

        PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);
        updateOrderStatusPort.updateOrderStatus(savedPaymentDomain.getOrderId(), "PAID");

        // outbox 에 PaymentCaptured 이벤트 기록 (같은 @Transactional).
        // OutboxPublisherScheduler 가 Kafka 로 발행 → PaymentEventKafkaConsumer 가 정산 생성.
        publishEventPort.publishPaymentCaptured(
                savedPaymentDomain.getId(),
                savedPaymentDomain.getOrderId(),
                savedPaymentDomain.getAmount(),
                savedPaymentDomain.getCapturedAt(),
                savedPaymentDomain.getPaymentMethod(),
                savedPaymentDomain.getPgTransactionId(),
                loadSellerSettlementMetaPort.findByPaymentId(savedPaymentDomain.getId()).orElse(null));
        log.info("PaymentCaptured event queued to outbox. paymentId={}", savedPaymentDomain.getId());

        return savedPaymentDomain;
    }
}
