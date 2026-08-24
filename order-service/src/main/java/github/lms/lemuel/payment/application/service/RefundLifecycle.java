package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 환불 시도 이력(refunds 행)의 상태 전이를 <b>본 환불 트랜잭션과 분리된 독립 트랜잭션</b>으로 커밋하는
 * 협력자. {@link github.lms.lemuel.payment.application.RefundPaymentUseCase} 가 사용한다.
 *
 * <p><b>왜 REQUIRES_NEW 인가.</b> 환불은 {@code approveRefund}(order) → {@code refundPayment}(payment)
 * 로 이어지며 전파 기본값(REQUIRED)이라 <b>하나의 물리 트랜잭션</b>을 공유한다. PG 호출이 실패하면 그
 * 공유 트랜잭션이 통째로 롤백되는데, 실패 <b>이력(FAILED)은 롤백돼서는 안 된다</b>(재시도/대사의 근거).
 * 따라서 이력 전이는 독립 트랜잭션으로 즉시 커밋한다.
 *
 * <p><b>왜 INSERT 를 락 획득 전에 하는가(교착 회피).</b> PostgreSQL 에서 자식 행 INSERT 는 부모(payments)
 * 행에 {@code FOR KEY SHARE} 를 잡는데, 이는 환불이 payment 를 잠글 때 쓰는 {@code FOR UPDATE} 와 충돌한다.
 * 공유 트랜잭션이 payment 를 {@code FOR UPDATE} 로 잡은 상태에서 REQUIRES_NEW 로 refunds 를 INSERT 하면
 * 서로를 기다리는 교착이 난다. 그래서 {@link #begin} 은 <b>락을 잡기 전에</b> REQUESTED 행을 INSERT 해
 * 커밋하고, 이후 성공/실패는 전부 <b>UPDATE</b>(부모 FK 재검사 없음 → 부모 락 안 잡음)라 교착이 없다.
 */
@Service
public class RefundLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RefundLifecycle.class);

    private final LoadRefundPort loadRefundPort;
    private final SaveRefundPort saveRefundPort;
    private final OpsSignalPort opsSignalPort;

    public RefundLifecycle(LoadRefundPort loadRefundPort, SaveRefundPort saveRefundPort,
                           OpsSignalPort opsSignalPort) {
        this.loadRefundPort = loadRefundPort;
        this.saveRefundPort = saveRefundPort;
        this.opsSignalPort = opsSignalPort;
    }

    /**
     * 시도 이력을 REQUESTED 로 확보한다. 동일 (paymentId, idempotencyKey) 행이 이미 있으면(재시도 등)
     * 그 행을 재사용하고, 없으면 새로 INSERT 한다. 반드시 payment {@code FOR UPDATE} 락을 잡기 전에 호출한다.
     *
     * @return 확보된 환불 이력(항상 id 가 채워진 상태)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund begin(Long paymentId, BigDecimal amount, String idempotencyKey, String reason) {
        return loadRefundPort.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                .orElseGet(() -> {
                    try {
                        return saveRefundPort.save(Refund.request(paymentId, amount, idempotencyKey, reason));
                    } catch (DataIntegrityViolationException race) {
                        // 락 밖에서 드물게 동시 요청이 같은 키로 먼저 INSERT 한 경우 — 그 행을 재사용.
                        return loadRefundPort.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                                .orElseThrow(() -> race);
                    }
                });
    }

    /**
     * PG 환불 실패를 기록한다(FAILED + 재시도 횟수 증가 + 다음 재시도 예약). 독립 트랜잭션으로 커밋되므로
     * 호출한 공유 트랜잭션이 롤백돼도 실패 이력은 보존된다. 대상은 이미 존재하는 행의 UPDATE 라 교착이 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long refundId, String reason) {
        Refund refund = loadRefundPort.findById(refundId).orElse(null);
        if (refund == null) {
            log.warn("환불 실패 기록 대상 없음 — refundId={} (이미 정리됐거나 미커밋)", refundId);
            return;
        }
        if (refund.isCompleted()) {
            // 성공한 환불을 실패로 되돌리지 않는다(다른 경로에서 먼저 완료된 경우).
            log.info("이미 COMPLETED 된 환불이라 실패 기록 생략. refundId={}", refundId);
            return;
        }
        refund.markFailed(reason);
        saveRefundPort.save(refund);
        log.warn("환불 실패 기록. refundId={}, retryCount={}, nextRetryAt={}, reason={}",
                refundId, refund.getRetryCount(), refund.getNextRetryAt(), reason);
        // 운영 관제 실패 신호 — best-effort(절대 throw 안 함). 환불(=결제 도메인) 실패를 operation-service 로 집계.
        opsSignalPort.emit(OpsSignalCategory.PAYMENT_FAILED, "refund", String.valueOf(refundId),
                Map.of("reason", "REFUND_FAILED", "retryCount", refund.getRetryCount()));
    }
}
