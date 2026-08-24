package github.lms.lemuel.payment.application;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.RefundException;
import github.lms.lemuel.payment.domain.exception.RefundExceedsPaymentException;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.in.CashReceiptUseCase;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.application.service.RefundLifecycle;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 결제 환불 유스케이스.
 *
 * <p><b>트랜잭션·이력 설계.</b> 환불 시도 이력(REQUESTED)을 payment {@code FOR UPDATE} 락을 잡기
 * <b>전에</b> {@link RefundLifecycle#begin} 으로 독립 커밋한 뒤 락을 잡고 PG 를 호출한다. 성공하면 본
 * 트랜잭션에서 이력을 COMPLETED 로 확정해 결제/주문/이벤트와 원자적으로 커밋하고, 실패하면
 * {@link RefundLifecycle#fail} 이 독립 트랜잭션으로 FAILED 를 남긴 뒤 예외를 던져 본(공유) 트랜잭션을
 * 롤백한다("환불에 성공한 경우에만 결제/주문 확정"). 이렇게 하면 유령 환불 없이 실패 이력이 보존돼
 * {@code RefundRetryScheduler} 의 자동 재시도 근거가 된다.
 *
 * <p>격리수준은 {@link Isolation#READ_COMMITTED} — lost update/PG 이중호출은 {@code FOR UPDATE}
 * 비관 락이 막고, {@code begin} 이 독립 커밋한 REQUESTED 행을 본 트랜잭션의 후속 UPDATE 가 볼 수 있어야
 * 하기 때문(REPEATABLE_READ 면 스냅샷 밖이라 UPDATE 가 0건 처리됨).
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RefundPaymentUseCase implements RefundPaymentPort {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentUseCase.class);
    private static final String FULL_REFUND_KEY_PREFIX = "payment-";
    private static final String FULL_REFUND_KEY_SUFFIX = "-full";

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final LoadRefundPort loadRefundPort;
    private final SaveRefundPort saveRefundPort;
    private final RefundLifecycle refundLifecycle;
    private final CashReceiptUseCase cashReceiptUseCase;

    public RefundPaymentUseCase(LoadPaymentPort loadPaymentPort,
                                SavePaymentPort savePaymentPort,
                                PgClientPort pgClientPort,
                                UpdateOrderStatusPort updateOrderStatusPort,
                                PublishEventPort publishEventPort,
                                LoadRefundPort loadRefundPort,
                                SaveRefundPort saveRefundPort,
                                RefundLifecycle refundLifecycle,
                                CashReceiptUseCase cashReceiptUseCase) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadRefundPort = loadRefundPort;
        this.saveRefundPort = saveRefundPort;
        this.refundLifecycle = refundLifecycle;
        this.cashReceiptUseCase = cashReceiptUseCase;
    }

    @Override
    @Auditable(
            action = AuditAction.REFUND_COMPLETED,
            failureAction = "REFUND_FAILED",
            resourceType = "Payment",
            resourceId = "#p0.toString()",
            detail = "{'paymentId': #p0, 'amount': #p1, 'idempotencyKeyProvided': #p2 != null && !#p2.isBlank(), 'paymentStatus': #result == null ? null : #result.getStatus().name()}"
    )
    public PaymentDomain refundPayment(Long paymentId, BigDecimal amount, String idempotencyKey) {
        // ── Phase 0: 락 밖 스냅샷으로 예비 검증 + 멱등 단축 반환 ──
        // 여기서는 결제 행을 잠그지 않는다(begin 의 REQUESTED INSERT 가 payment FK 락과 교착하지 않도록).
        // 잘못된 요청은 begin 이전에 걸러 REQUESTED 이력이 유효 요청에만 생기게 한다. 상태가 스냅샷 이후
        // 바뀔 수 있으므로 Phase 2 에서 락 안 권위 검증을 한 번 더 한다.
        PaymentDomain snapshot = loadPaymentPort.loadById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        boolean isFullRefund = (amount == null);
        String effectiveKey = resolveIdempotencyKey(paymentId, idempotencyKey, isFullRefund);

        Refund existing = loadRefundPort.findByPaymentIdAndIdempotencyKey(paymentId, effectiveKey)
                .orElse(null);
        // 동일 키로 이미 COMPLETED 된 환불이 있으면 PG 재호출 없이 멱등 반환.
        if (existing != null && existing.isCompleted()) {
            log.info("Refund already completed. paymentId={}, key={}", paymentId, effectiveKey);
            return snapshot;
        }
        // 이미 전액 환불된 결제엔 더 환불할 수 없다. 재시도 중이던 실패건이 있으면 "적용 불가"로 보고
        // 자동 재시도를 중단(관리자 검토 대상)한 뒤 멱등 반환한다 — 없으면 그냥 no-op 반환.
        if (snapshot.getStatus() == PaymentStatus.REFUNDED) {
            if (existing != null) {
                existing.abandon("payment already fully refunded — refund cannot be applied");
                saveRefundPort.save(existing);
                log.warn("환불 재시도 중단(결제 이미 전액 환불). refundId={}, key={}", existing.getId(), effectiveKey);
            }
            log.info("Payment already fully refunded, skipping. paymentId={}", paymentId);
            return snapshot;
        }
        if (snapshot.getStatus() != PaymentStatus.CAPTURED) {
            throw new InvalidPaymentStateException(
                    "Payment must be in CAPTURED status to refund. Current: " + snapshot.getStatus());
        }
        BigDecimal plannedAmount = isFullRefund ? snapshot.getRefundableAmount() : amount;
        if (plannedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentInvariantViolationException("Refund amount must be greater than zero");
        }
        if (plannedAmount.compareTo(snapshot.getRefundableAmount()) > 0) {
            throw new RefundExceedsPaymentException(
                    "Refund amount " + plannedAmount + " exceeds refundable " + snapshot.getRefundableAmount());
        }

        // ── Phase 1: 시도 이력(REQUESTED)을 락 획득 전에 독립 커밋 ──
        // 실패 시 이 행을 UPDATE(FAILED)만 하므로 부모 FK 락을 잡지 않아 교착이 없다.
        Refund refund = (existing != null)
                ? existing
                : refundLifecycle.begin(paymentId, plannedAmount, effectiveKey,
                        isFullRefund ? "FULL_REFUND" : "PARTIAL_REFUND");

        // ── Phase 2: 비관 락 + 권위 검증 + PG 호출 ──
        // 비관적 락: 동시 환불이 같은 결제 행을 읽고 각자 refundedAmount 를 덮어쓰는 lost update /
        // PG 이중 호출을 막기 위해 트랜잭션 종료까지 행을 잠근다.
        PaymentDomain payment = loadPaymentPort.loadByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment; // 락 대기 중 다른 트랜잭션이 전액 환불 완료 — 멱등 반환
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new InvalidPaymentStateException(
                    "Payment must be in CAPTURED status to refund. Current: " + payment.getStatus());
        }
        // 락 안에서 권위 금액 재확정(스냅샷 이후 잔액 변동 반영) 및 초과 검증.
        BigDecimal refundAmount = isFullRefund ? payment.getRefundableAmount() : amount;
        if (refundAmount.compareTo(payment.getRefundableAmount()) > 0) {
            throw new RefundExceedsPaymentException(
                    "Refund amount " + refundAmount + " exceeds refundable " + payment.getRefundableAmount());
        }

        // PG 환불 호출. 실패하면 독립 트랜잭션으로 FAILED 이력을 남기고(재시도 근거) 예외를 던져
        // 공유 트랜잭션(결제/주문/이벤트)을 롤백한다 → "환불 성공 시에만 확정" + 유령 환불 방지.
        // 원시 PG 예외(HttpServerErrorException 등)는 RefundException 으로 변환해 502 누수 대신
        // REFUND_ERROR(500) 로 일관 매핑하고, @Auditable(failureAction=REFUND_FAILED) 로 감사한다.
        try {
            // effectiveKey 를 PG 멱등 키로 전달 — 재시도 시 같은 키라 PG 가 이중 환불하지 않는다
            // ("PG 는 됐는데 우리 DB 는 실패" 후 자동 재시도의 이중 환불 방지).
            pgClientPort.refund(payment.getPgTransactionId(), refundAmount, effectiveKey);
        } catch (RefundException e) {
            refundLifecycle.fail(refund.getId(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("PG refund failed — recording FAILED and rolling back. paymentId={}, key={}, amount={}",
                    paymentId, effectiveKey, refundAmount, e);
            refundLifecycle.fail(refund.getId(), "PG refund failed: " + e.getMessage());
            throw new RefundException(
                    "PG refund failed for paymentId=" + paymentId + ", amount=" + refundAmount, e);
        }

        // ── Phase 3: 성공 — 이력을 COMPLETED 로 확정하고 결제/주문/이벤트를 원자적으로 커밋 ──
        refund.correctAmount(refundAmount); // 스냅샷과 권위 금액이 다를 경우(동시 부분환불) 최종값으로 정정
        refund.markCompleted();
        Refund completedRefund = saveRefundPort.save(refund);

        // 전액 환불이면 REFUNDED 전이, 부분 환불이면 refundedAmount 누적 + 전액 도달 시 REFUNDED.
        payment.addRefundedAmount(refundAmount);
        if (payment.isFullyRefunded()) {
            payment.refund();
        }
        PaymentDomain savedPaymentDomain = savePaymentPort.save(payment);

        // 전액 환불 도달 시점에만 주문 상태 REFUNDED 로 전이 — 부분 환불은 주문 상태 변경 없음.
        if (savedPaymentDomain.getStatus() == PaymentStatus.REFUNDED) {
            updateOrderStatusPort.updateOrderStatus(savedPaymentDomain.getOrderId(), "REFUNDED");
            // 돈을 전부 돌려줬으면 현금영수증도 취소한다 — 남겨 두면 받지 않은 돈에 소득공제·
            // 매입세액공제가 붙는다. 현금 결제가 아니었거나 신청하지 않은 주문이면 no-op 이고,
            // 취소 자체가 실패해도 예외를 올리지 않는다(PG 환불은 이미 끝났다 — 롤백하면 유령 환불).
            cashReceiptUseCase.cancelForPayment(savedPaymentDomain.getId(), "결제 전액 환불");
        }

        // 이벤트 발행 — settlement 모듈은 PaymentRefunded Kafka 이벤트로 자체 정산 조정.
        // 누적(refundedAmount)은 프로젝션 뷰 갱신, 건별(refundAmount)+refundId 는 역정산 트리거에 쓰인다.
        publishEventPort.publishPaymentRefunded(savedPaymentDomain.getId(), savedPaymentDomain.getOrderId(),
                savedPaymentDomain.getRefundedAmount(), refundAmount, completedRefund.getId());
        log.info("PaymentRefunded event published. paymentId={}, refundId={}, refundAmount={}",
                savedPaymentDomain.getId(), completedRefund.getId(), refundAmount);

        return savedPaymentDomain;
    }

    /** 멱등 키 결정: 전액 환불은 기본 키 자동 생성, 부분 환불은 호출자가 반드시 지정. */
    private String resolveIdempotencyKey(Long paymentId, String idempotencyKey, boolean isFullRefund) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        if (isFullRefund) {
            return fullRefundKey(paymentId);
        }
        throw new MissingIdempotencyKeyException("Partial refund requires an explicit idempotency key");
    }

    private static String fullRefundKey(Long paymentId) {
        return FULL_REFUND_KEY_PREFIX + paymentId + FULL_REFUND_KEY_SUFFIX;
    }
}
