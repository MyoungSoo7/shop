package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.Refund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoadRefundPort {

    Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    Optional<Refund> findById(Long id);

    /**
     * 결제별 전체 환불 이력 — 관리자·사용자가 환불 내역을 조회할 때 사용.
     * 결과는 requestedAt 내림차순.
     */
    List<Refund> findAllByPaymentId(Long paymentId);

    /**
     * 자동 재시도 대상 환불 — 상태가 FAILED 이고 다음 재시도 시각이 {@code now} 이전(도래)인 건.
     * 재시도 상한에 도달해 {@code nextRetryAt} 이 비워진(NULL) 건은 제외된다.
     * {@code RefundRetryScheduler} 가 주기적으로 호출한다.
     */
    List<Refund> findRetryable(LocalDateTime now);

    /** 상태별 환불 조회(최신순) — 관리자 콘솔(/admin/refunds?status=FAILED) 전용. */
    List<Refund> findByStatus(Refund.Status status);
}
