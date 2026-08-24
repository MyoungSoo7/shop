package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentDomain.TenderRefundPlan;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 분할결제 환불 오케스트레이터 — 역순(sequence 큰 tender 부터) 처리.
 *
 * <p>예) 50,000원 = POINT(seq=1, 5,000) + GIFT_CARD(seq=2, 10,000) + CARD(seq=3, 35,000)
 * 에서 30,000원 환불 요청 시 CARD(seq=3) 부터 차감 → CARD 잔여 5,000.
 *
 * <p>왜 역순인가? 외부 PG (CARD) 가 먼저 취소되어야 실 거래가 사라지고, 내부 잔액
 * (POINT/GIFT_CARD) 은 실패해도 운영자가 수동 복원 가능.
 *
 * <p><b>트랜잭션 경계 (중요):</b> 예전엔 클래스 레벨 {@code @Transactional} 이 tender 루프 전체를
 * 감싸 PG 호출을 DB 트랜잭션 안에서 여러 번 수행했다. 한 tender 실패 시 트랜잭션이 통째로
 * 롤백되어 <b>이미 PG 환불된 앞선 tender 의 DB 상태까지 되돌아가</b> DB-PG 정합성이 깨졌고,
 * DB 커넥션을 외부 I/O 동안 점유했다. 이제 본 서비스는 트랜잭션을 들지 않는 오케스트레이터로,
 * 계획을 세운 뒤 tender 마다 {@link TenderRefundExecutor#refundTender}({@code REQUIRES_NEW})를
 * 호출해 건별로 독립 커밋한다. 중간 실패 시 앞선 환불은 PG 실거래와 일치하게 보존되고, 뒤
 * tender 는 중단되어 운영자 대사 대상으로 남는다.
 */
@Service
public class RefundSplitPaymentService {

    private static final Logger log = LoggerFactory.getLogger(RefundSplitPaymentService.class);

    private final LoadPaymentPort loadPaymentPort;
    private final TenderRefundExecutor tenderRefundExecutor;

    public RefundSplitPaymentService(LoadPaymentPort loadPaymentPort,
                                     TenderRefundExecutor tenderRefundExecutor) {
        this.loadPaymentPort = loadPaymentPort;
        this.tenderRefundExecutor = tenderRefundExecutor;
    }

    public PaymentDomain refundSplit(Long paymentId, BigDecimal totalRefundAmount) {
        // 1) 스냅샷 읽기로 환불 계획 수립. 실제 적용은 tender 별 트랜잭션에서 락 잡고 재검증하므로
        //    여기서는 락 없이 읽어도 안전하다(over-refund 는 addRefund 가 차단).
        PaymentDomain snapshot = loadPaymentPort.loadById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (!snapshot.isSplit()) {
            throw new InvalidPaymentStateException("단일결제는 RefundPaymentUseCase 사용");
        }
        if (snapshot.getStatus() != PaymentStatus.CAPTURED) {
            throw new InvalidPaymentStateException("CAPTURED 결제만 환불 가능: " + snapshot.getStatus());
        }

        List<TenderRefundPlan> plans = snapshot.planRefundFromTenders(totalRefundAmount);

        // 2) tender 별 독립 트랜잭션(REQUIRES_NEW)으로 환불. 한 건 실패는 즉시 전파되며,
        //    이미 커밋된 앞선 tender 환불은 롤백되지 않는다(= PG 실거래와 일치).
        int applied = 0;
        for (TenderRefundPlan plan : plans) {
            tenderRefundExecutor.refundTender(paymentId, plan.tender().getId(), plan.amount());
            applied++;
        }

        // 3) 계획된 tender 가 모두 성공한 경우에만 종료 이벤트 1회 발행(정산 조정 트리거).
        tenderRefundExecutor.finalizeRefund(paymentId, totalRefundAmount);

        log.info("분할결제 환불 완료: paymentId={}, refundAmount={}, plansApplied={}",
                paymentId, totalRefundAmount, applied);

        return loadPaymentPort.loadById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
