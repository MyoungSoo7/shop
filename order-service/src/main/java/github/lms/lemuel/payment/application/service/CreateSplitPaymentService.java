package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.GiftCardTenderPort;
import github.lms.lemuel.payment.application.port.out.PointTenderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 텐더 기반 결제 생성 서비스 — 지불수단이 하나든 여럿이든 이 경로 하나를 탄다.
 *
 * <p>흐름:
 * <ol>
 *   <li>요청된 tender 들을 sequence 순서로 PaymentTender 도메인으로 변환</li>
 *   <li>외부 PG tender → PgRouter.authorize/capture (각 tender 별 독립 PG 거래)</li>
 *   <li>내부 잔액 tender → 외부 호출 없이 즉시 CAPTURED, 저장 후 원장에서 실제 차감</li>
 *   <li>모든 tender 가 성공하면 {@code PaymentDomain.createWithTenders} + 저장 + 주문 PAID 전이 + outbox 이벤트</li>
 *   <li>중간에 실패 시 트랜잭션 롤백 → 이미 처리된 외부 PG 거래는 별도 보상 처리 필요 (Saga, 본 구현은 단순화)</li>
 * </ol>
 *
 * <p><b>클래스 이름에 "Split" 이 남아 있는 이유</b>: 이제 텐더 1 개(포인트·기프트카드 전액 결제)도
 * 받으므로 이름이 좁아졌지만, 클래스·REST 경로({@code /payments/split})까지 바꾸면 게이트웨이·
 * nginx 배선과 외부 계약이 함께 움직인다. 의미가 넓어진 것은 메서드 이름
 * ({@code createWithTenders})과 이 주석으로 표시하고, 경로 개명은 별건으로 남긴다.
 */
@Service
@Transactional
public class CreateSplitPaymentService implements CreateSplitPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateSplitPaymentService.class);

    private final PgClientPort pgClientPort;
    private final SavePaymentPort savePaymentPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    private final PointTenderPort pointTenderPort;
    private final GiftCardTenderPort giftCardTenderPort;

    public CreateSplitPaymentService(PgClientPort pgClientPort,
                                      SavePaymentPort savePaymentPort,
                                      UpdateOrderStatusPort updateOrderStatusPort,
                                      PublishEventPort publishEventPort,
                                      github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort,
                                      PointTenderPort pointTenderPort,
                                      GiftCardTenderPort giftCardTenderPort) {
        this.pgClientPort = pgClientPort;
        this.savePaymentPort = savePaymentPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadSellerSettlementMetaPort = loadSellerSettlementMetaPort;
        this.pointTenderPort = pointTenderPort;
        this.giftCardTenderPort = giftCardTenderPort;
    }

    @Override
    public PaymentDomain createWithTenders(Long orderId, List<TenderRequest> tenderRequests, Long actorUserId) {
        if (tenderRequests == null || tenderRequests.isEmpty()) {
            throw new PaymentInvariantViolationException("결제에는 최소 1 개의 지불수단이 필요합니다");
        }

        log.info("텐더 결제 시작: orderId={}, tenders={}", orderId, tenderRequests.size());

        List<PaymentTender> tenders = new ArrayList<>(tenderRequests.size());
        int seq = 1;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (TenderRequest req : tenderRequests) {
            PaymentTender tender = PaymentTender.newTender(req.type(), req.amount(), seq++);
            tenders.add(tender);
            totalAmount = totalAmount.add(req.amount());
        }

        // 포인트 사용 상한 검사 — PG 를 부르기 전에 끊는다. 승인 뒤에 거절하면 취소 보상이 필요해진다.
        BigDecimal pointAmount = tenders.stream()
                .filter(t -> t.getType() == TenderType.POINT)
                .map(PaymentTender::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        pointTenderPort.assertWithinUsageLimit(totalAmount, pointAmount);

        // 가장 큰 tender 의 type 을 paymentMethod 표시값으로 사용 (운영자 가시성)
        String paymentMethod = pickPrimaryMethodLabel(tenderRequests);
        PaymentDomain payment = PaymentDomain.createWithTenders(orderId, tenders, paymentMethod);

        boolean awaitsDeposit = payment.awaitsDeposit();

        // 각 tender 처리
        for (PaymentTender tender : tenders) {
            processTender(tender, orderId, awaitsDeposit);
        }

        if (awaitsDeposit) {
            // 부모 결제는 READY 로 남는다 — 돈이 아직 안 들어왔고, 미입금 만료 배치가 집어갈 수
            // 있어야 하기 때문이다(PaymentDomain.expire 는 READY 에서만 EXPIRED 에 도달한다).
            // 주문도 PAID 로 올리지 않고 payment.captured 도 발행하지 않는다 — 발행하면 입금되지
            // 않은 주문이 그대로 정산 대상이 된다.
            PaymentDomain pending = savePaymentPort.save(payment);
            holdInternalTenders(pending, actorUserId);
            log.info("입금 대기 결제 생성: paymentId={}, orderId={}, amount={}",
                    pending.getId(), orderId, pending.getAmount());
            return pending;
        }

        // 부모 Payment 캡처 + 저장
        payment.authorize("SPLIT-" + orderId);  // 합산 식별자 (실 운영은 별도 정책)
        payment.capture();
        PaymentDomain saved = savePaymentPort.save(payment);

        // 포인트 차감은 저장 이후다 — 원장 멱등 키가 tenderId 라, 식별자가 확정되기 전에는
        // 같은 결제를 두 번 차감했는지 구분할 방법이 없다. 같은 트랜잭션이므로 실패하면 함께 롤백된다.
        deductInternalTenders(saved, actorUserId);

        updateOrderStatusPort.updateOrderStatus(saved.getOrderId(), "PAID");
        publishEventPort.publishPaymentCaptured(saved.getId(), saved.getOrderId(), saved.getAmount(),
                saved.getCapturedAt(),
                saved.getPaymentMethod(),
                saved.getPgTransactionId(),
                loadSellerSettlementMetaPort.findByPaymentId(saved.getId()).orElse(null));

        log.info("분할결제 완료: paymentId={}, totalAmount={}, tenders={}",
                saved.getId(), saved.getAmount(), saved.getTenders().size());
        return saved;
    }

    /**
     * @param awaitsDeposit 입금 대기 결제면 <b>승인까지만</b> 하고 캡처하지 않는다. 카드 텐더도
     *                      마찬가지다 — 가상계좌 입금이 끝내 오지 않아 주문이 취소될 수 있는데,
     *                      카드만 먼저 매입해 두면 그때 환불로 되돌려야 한다.
     */
    private void processTender(PaymentTender tender, Long orderId, boolean awaitsDeposit) {
        if (tender.getType().usesExternalPg()) {
            // 외부 PG 호출 — PgRouter 가 자동으로 적합한 PG 어댑터 선택
            String pgTxnId = pgClientPort.authorize(orderId, tender.getAmount(), tender.getType().name());
            tender.authorize(pgTxnId);
            if (!awaitsDeposit) {
                pgClientPort.capture(pgTxnId, tender.getAmount());
                tender.capture();
            }
            log.debug("외부 PG tender 처리: type={}, amount={}, pgTxn={}, 입금대기={}",
                    tender.getType(), tender.getAmount(), pgTxnId, awaitsDeposit);
        } else {
            // 내부 잔액 tender — 외부 호출은 없다. 실제 원장 차감(또는 선점)은 저장 후에 한다
            // (원장 자연키가 tenderId 인데, 저장 전에는 그 식별자가 없기 때문).
            tender.authorize(null);
            if (!awaitsDeposit) {
                tender.capture();
            }
            log.debug("내부 잔액 tender 처리: type={}, amount={}, 입금대기={}",
                    tender.getType(), tender.getAmount(), awaitsDeposit);
        }
    }

    /**
     * 내부 잔액 tender 를 <b>선점</b>한다 — 차감이 아니다. 입금이 확인되면 확정되고, 기한이 지나면
     * 풀린다. 저장 이후인 이유는 선점의 자연키가 tenderId 이기 때문이다(차감과 같은 이유).
     */
    private void holdInternalTenders(PaymentDomain saved, Long actorUserId) {
        for (PaymentTender tender : saved.getTenders()) {
            if (tender.getType().usesExternalPg()) {
                continue;
            }
            if (actorUserId == null) {
                throw new PaymentInvariantViolationException(
                        "내부 잔액 선점에는 인증 주체가 필요합니다: paymentId=" + saved.getId());
            }
            if (tender.getType() == TenderType.POINT) {
                pointTenderPort.hold(actorUserId, tender.getAmount(), tender.getId());
            } else if (tender.getType() == TenderType.GIFT_CARD) {
                giftCardTenderPort.hold(actorUserId, tender.getAmount(), tender.getId());
            }
        }
    }

    /**
     * 내부 잔액 tender(POINT·GIFT_CARD)를 각자의 원장에서 실제로 차감한다.
     *
     * <p>여기가 "장부 없는 결제 수단"을 닫는 지점이다. 잔액이 모자라면 예외가 올라와 결제 전체가
     * 롤백된다 — 검증 없이 통과시키던 이전 동작보다 결제 실패가 옳다.
     *
     * <p>차감이 저장 이후인 이유: 두 원장 모두 멱등 키가 tenderId 인데, 저장 전에는 그 식별자가
     * 없어 같은 결제를 두 번 차감했는지 구분할 수 없다.
     */
    private void deductInternalTenders(PaymentDomain saved, Long actorUserId) {
        for (PaymentTender tender : saved.getTenders()) {
            if (tender.getType().usesExternalPg()) {
                continue;
            }
            if (actorUserId == null) {
                // 주체를 모른 채 남의 잔액을 건드릴 수는 없다.
                throw new PaymentInvariantViolationException(
                        "내부 잔액 결제에는 인증 주체가 필요합니다: paymentId=" + saved.getId());
            }
            if (tender.getType() == TenderType.POINT) {
                pointTenderPort.use(actorUserId, tender.getAmount(), tender.getId());
            } else if (tender.getType() == TenderType.GIFT_CARD) {
                giftCardTenderPort.use(actorUserId, tender.getAmount(), tender.getId());
            }
        }
    }

    /**
     * 운영 화면에 보일 결제수단 표시값.
     *
     * <p>지불수단이 하나뿐이면 <b>{@code "SPLIT:"} 을 붙이지 않는다</b>. 붙이면 분할결제가 아닌
     * 결제가 운영 화면·정산 프로젝션에서 분할결제로 읽힌다 — 표시값은 사실이어야 한다.
     * 이 값은 {@code CashReceipt.isCashTender} 가 보는 값이기도 해서, 접두어가 붙어 있으면
     * 단일 계좌이체 결제가 현금영수증 발급 대상에서 조용히 빠진다.
     */
    private String pickPrimaryMethodLabel(List<TenderRequest> reqs) {
        TenderType primary = reqs.stream()
                .max((a, b) -> a.amount().compareTo(b.amount()))
                .map(TenderRequest::type)
                .orElse(TenderType.CARD);
        return reqs.size() == 1 ? primary.name() : "SPLIT:" + primary.name();
    }
}
