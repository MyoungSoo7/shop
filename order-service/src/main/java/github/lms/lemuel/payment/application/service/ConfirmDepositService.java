package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.ConfirmDepositUseCase;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
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
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.domain.exception.PaymentOwnershipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입금 확인 — 가상계좌·무통장 결제에 돈이 들어왔을 때 결제를 확정한다.
 *
 * <p>결제 생성 시점에는 승인만 해 두고 아무것도 확정하지 않았다. 여기서 처음으로 PG 매입,
 * 포인트 선점 확정(로트 소비·USE 엔트리), 주문 PAID 전이, {@code payment.captured} 발행이 일어난다.
 *
 * <p><b>비관적 락으로 재조회</b>한다. 입금 통보와 미입금 만료 배치는 독립적으로 도착해 경합하며,
 * 락 없이 스냅샷을 믿으면 둘 다 성공해 "취소된 주문이 결제 완료로 되살아난다".
 *
 * <p><b>멱등</b>이 기능의 일부다 — 웹훅은 같은 통보를 여러 번 보내는 것이 정상이다. 이미 CAPTURED
 * 면 아무것도 다시 하지 않고 그대로 돌려준다. 다시 하면 PG 이중 매입·포인트 이중 차감이 된다.
 *
 * <p>순서는 <b>PG 매입 → 선점 확정 → 상태 전이 → 이벤트</b>다. 외부 왕복(PG)이 실패하면 아무것도
 * 확정되지 않아야 하므로 가장 먼저 시도하고, 이벤트는 모든 것이 확정된 뒤 마지막에 나간다.
 */
@Service
@Transactional
public class ConfirmDepositService implements ConfirmDepositUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmDepositService.class);

    private final LoadPaymentPort loadPaymentPort;
    private final LoadOrderPort loadOrderPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    private final PointTenderPort pointTenderPort;
    private final GiftCardTenderPort giftCardTenderPort;

    public ConfirmDepositService(LoadPaymentPort loadPaymentPort,
                                 LoadOrderPort loadOrderPort,
                                 SavePaymentPort savePaymentPort,
                                 PgClientPort pgClientPort,
                                 UpdateOrderStatusPort updateOrderStatusPort,
                                 PublishEventPort publishEventPort,
                                 LoadSellerSettlementMetaPort loadSellerSettlementMetaPort,
                                 PointTenderPort pointTenderPort,
                                 GiftCardTenderPort giftCardTenderPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.loadOrderPort = loadOrderPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadSellerSettlementMetaPort = loadSellerSettlementMetaPort;
        this.pointTenderPort = pointTenderPort;
        this.giftCardTenderPort = giftCardTenderPort;
    }

    @Override
    public PaymentDomain confirmDeposit(Long paymentId, Long actorUserId, Long ownerUserId) {
        PaymentDomain payment = loadPaymentPort.loadByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // ★ 소유권 대조를 가장 먼저 한다 — 멱등 단축 반환보다도 앞이다.
        //   이 경로는 <b>돈이 실제로 움직이는</b> 지점이다: 외부 PG 매입 + 포인트/상품권 선점 확정
        //   + 주문 PAID + settlement 이벤트. paymentId 만 알면 남의 결제를 확정할 수 있으면,
        //   공격자는 피해자의 포인트 선점을 소진시키고 그 주문을 결제 완료로 되살릴 수 있다
        //   (선점 확정은 hold 가 가리키는 <b>피해자 계정</b>에서 차감된다 — actorUserId 는 감사 문자열일 뿐이다).
        //   이미 CAPTURED 인 건의 단축 반환도 대조 뒤에 둔다 — 남의 결제 상태를 조회하는 창구가 되면 안 된다.
        requireOwnedPayment(payment, ownerUserId);

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            log.info("입금 확인 멱등 단축 반환: paymentId={}", paymentId);
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.READY) {
            // 만료·취소가 먼저 이겼다. 여기서 확정하면 취소된 주문이 결제 완료로 되살아난다.
            throw new InvalidPaymentStateException(
                    "입금 대기 상태의 결제만 확정할 수 있습니다: " + payment.getStatus());
        }
        if (!payment.awaitsDeposit()) {
            throw new PaymentInvariantViolationException(
                    "입금을 기다리는 결제가 아닙니다: paymentId=" + paymentId
                            + ", method=" + payment.getPaymentMethod());
        }

        // 1) 외부 PG 매입 — 실패하면 아무것도 확정되지 않아야 하므로 가장 먼저 시도한다.
        for (PaymentTender tender : payment.getTenders()) {
            if (tender.getType().usesExternalPg()) {
                pgClientPort.capture(tender.getPgTransactionId(), tender.getAmount());
                tender.capture();
            }
        }

        // 2) 내부 잔액 선점 확정 — 여기서 비로소 로트가 소비되고 USE 엔트리가 남는다.
        for (PaymentTender tender : payment.getTenders()) {
            if (tender.getType().usesExternalPg()) {
                continue;
            }
            if (actorUserId == null) {
                throw new PaymentInvariantViolationException(
                        "선점 확정에는 인증 주체가 필요합니다: paymentId=" + paymentId);
            }
            if (tender.getType() == TenderType.POINT) {
                pointTenderPort.captureHold(tender.getId(), actorUserId);
            } else if (tender.getType() == TenderType.GIFT_CARD) {
                giftCardTenderPort.captureHold(tender.getId(), actorUserId);
            }
            tender.capture();
        }

        // 3) 부모 결제 확정.
        payment.authorize("DEPOSIT-" + payment.getOrderId());
        payment.capture();
        PaymentDomain saved = savePaymentPort.save(payment);

        // 4) 주문 전이와 이벤트는 모든 것이 확정된 뒤 마지막에.
        updateOrderStatusPort.updateOrderStatus(saved.getOrderId(), "PAID");
        publishEventPort.publishPaymentCaptured(saved.getId(), saved.getOrderId(), saved.getAmount(),
                saved.getCapturedAt(), saved.getPaymentMethod(), saved.getPgTransactionId(),
                loadSellerSettlementMetaPort.findByPaymentId(saved.getId()).orElse(null));

        log.info("입금 확인 완료: paymentId={}, orderId={}, amount={}",
                saved.getId(), saved.getOrderId(), saved.getAmount());
        return saved;
    }

    /**
     * 결제가 걸린 주문의 소유자와 인증 주체를 대조한다.
     *
     * <p>{@code ownerUserId} 가 {@code null} 이면 운영자 경로라 건너뛴다 — 그 판정(ADMIN·MANAGER)은
     * 웹 어댑터가 하고, 애플리케이션 계층은 스프링 시큐리티를 알지 않는다.
     *
     * <p>주문 소유자를 알 수 없으면(<b>userId 가 null</b>) 통과가 아니라 거부한다(fail-closed).
     * "모르면 막는다"가 돈 경로의 기본값이다 — {@code LoadOrderPort.OrderInfo} 의 3-인자 생성자
     * 주석과 같은 규약이다.
     */
    private void requireOwnedPayment(PaymentDomain payment, Long ownerUserId) {
        if (ownerUserId == null) {
            return;
        }
        LoadOrderPort.OrderInfo order = loadOrderPort.loadOrder(payment.getOrderId());
        if (order == null || order.getUserId() == null || !order.getUserId().equals(ownerUserId)) {
            throw new PaymentOwnershipException(payment.getOrderId());
        }
    }
}
