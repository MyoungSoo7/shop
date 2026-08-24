package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.GiftCardTenderPort;
import github.lms.lemuel.payment.application.port.out.PointTenderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 분할결제 환불의 tender 단위 실행 트랜잭션 경계.
 *
 * <p><b>왜 분리했나:</b> 분할결제 환불은 tender 마다 외부 PG/내부잔액을 따로 건드린다. 예전엔
 * 모든 tender 환불을 하나의 {@code @Transactional} 안에서 루프 돌며 PG 를 호출했는데, 2번째
 * tender PG 환불이 실패하면 트랜잭션이 통째로 롤백되면서 <b>이미 PG 에서 환불된 1번째 tender 의
 * DB 상태까지 되돌아가</b> DB("환불 안 됨") vs PG("환불됨") 정합성이 깨졌다.
 *
 * <p>이 executor 는 tender 1건을 {@code REQUIRES_NEW} 로 독립 커밋한다. 따라서 앞선 tender 의
 * 환불은 다음 tender 실패와 무관하게 DB 에 보존되어 PG 실거래와 일치한다. 오케스트레이터
 * ({@link RefundSplitPaymentService})는 실패 시 뒤따르는 tender 를 중단하고, 이미 커밋된
 * 부분 환불은 운영자 대사(reconcile) 대상으로 남긴다.
 *
 * <p>동시성: 매 tender 트랜잭션에서 부모 결제 행을 {@code FOR UPDATE} 로 잠가, 동시 환불 간
 * {@code refundedAmount} lost update 와 tender 초과 환불을 직렬화로 차단한다.
 */
@Service
public class TenderRefundExecutor {

    private static final Logger log = LoggerFactory.getLogger(TenderRefundExecutor.class);

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final PointTenderPort pointTenderPort;
    private final GiftCardTenderPort giftCardTenderPort;

    public TenderRefundExecutor(LoadPaymentPort loadPaymentPort,
                                SavePaymentPort savePaymentPort,
                                PgClientPort pgClientPort,
                                UpdateOrderStatusPort updateOrderStatusPort,
                                PublishEventPort publishEventPort,
                                PointTenderPort pointTenderPort,
                                GiftCardTenderPort giftCardTenderPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.pointTenderPort = pointTenderPort;
        this.giftCardTenderPort = giftCardTenderPort;
    }

    /**
     * tender 1건 환불을 독립 트랜잭션으로 확정한다. 외부 PG tender 는 PG 환불 호출, 내부
     * 잔액(POINT/GIFT_CARD)은 잔액 복원. 마지막 tender 로 전액 환불이 완성되면 결제·주문 상태도
     * 같은 커밋에서 REFUNDED 로 전이한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundTender(Long paymentId, Long tenderId, BigDecimal portion) {
        PaymentDomain payment = loadPaymentPort.loadByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        PaymentTender tender = payment.getTenders().stream()
                .filter(t -> tenderId.equals(t.getId()))
                .findFirst()
                .orElseThrow(() -> new PaymentInvariantViolationException(
                        "Tender not found: paymentId=" + paymentId + ", tenderId=" + tenderId));

        if (tender.getType().usesExternalPg()) {
            // tender·금액 단위로 안정적인 멱등 키 — 같은 tender 를 같은 금액으로 재환불 요청해도 PG 이중환불 방지.
            String idempotencyKey = "tender-" + tender.getId() + "-" + portion.stripTrailingZeros().toPlainString();
            pgClientPort.refund(tender.getPgTransactionId(), portion, idempotencyKey);
            log.info("외부 PG 환불: tenderId={}, type={}, amount={}",
                    tender.getId(), tender.getType(), portion);
        } else if (tender.getType() == TenderType.POINT) {
            // 환불 멱등 키(tender + 금액)를 원장 참조로 그대로 넘긴다 — 같은 부분환불을 두 번
            // 반영하려는 시도는 원장 자연키가 막는다. 복원 대상 계정은 원장이 알고 있으므로
            // 호출자가 사용자 식별자를 넘기지 않는다(환불은 낸 사람에게 돌아가야 한다).
            String refundReference = "tender-" + tender.getId() + "-"
                    + portion.stripTrailingZeros().toPlainString();
            pointTenderPort.restore(portion, tender.getId(), refundReference);
            log.info("포인트 복원: tenderId={}, amount={}", tender.getId(), portion);
        } else if (tender.getType() == TenderType.GIFT_CARD) {
            String refundReference = "tender-" + tender.getId() + "-"
                    + portion.stripTrailingZeros().toPlainString();
            giftCardTenderPort.restore(portion, tender.getId(), refundReference);
            log.info("기프트카드 복원: tenderId={}, amount={}", tender.getId(), portion);
        } else {
            // 내부잔액 텐더가 늘어났는데 원장을 붙이지 않은 경우다 — 조용히 통과시키지 않는다.
            log.warn("원장 없는 내부잔액 환불: tenderId={}, type={}, amount={}",
                    tender.getId(), tender.getType(), portion);
        }

        // PG/내부 적용 후에만 도메인 상태 갱신 — addRefund 가 잔여 초과를 재검증(동시성 가드).
        tender.addRefund(portion);
        payment.addRefundedAmount(portion);
        if (payment.isFullyRefunded()) {
            payment.refund();
            updateOrderStatusPort.updateOrderStatus(payment.getOrderId(), "REFUNDED");
        }
        savePaymentPort.save(payment);
    }

    /**
     * 환불 1건(요청)의 종료 처리 — 정산 서비스가 조정하도록 PaymentRefunded 이벤트를 1회 발행한다.
     * 계획된 tender 가 모두 성공한 뒤에만 호출되므로, 부분 실패로 중단된 환불은 이벤트를 내지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRefund(Long paymentId, BigDecimal refundAmount) {
        PaymentDomain payment = loadPaymentPort.loadById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        // 분할결제 경로는 Refund 엔티티를 만들지 않으므로 refundId=null — 역정산은 수행되고 원장 역분개만 생략된다.
        publishEventPort.publishPaymentRefunded(payment.getId(), payment.getOrderId(),
                payment.getRefundedAmount(), refundAmount, null);
        log.info("분할결제 환불 이벤트 발행: paymentId={}, status={}, refunded={}/{}",
                payment.getId(), payment.getStatus(), payment.getRefundedAmount(), payment.getAmount());
    }
}
