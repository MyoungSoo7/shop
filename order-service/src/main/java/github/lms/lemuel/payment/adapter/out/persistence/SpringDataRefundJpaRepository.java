package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataRefundJpaRepository extends JpaRepository<RefundJpaEntity, Long> {

    Optional<RefundJpaEntity> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    List<RefundJpaEntity> findByPaymentIdOrderByRequestedAtDesc(Long paymentId);

    /**
     * 자동 재시도 대상 — 재시도 시각이 도래한 FAILED 건. LessThanEqual 은 NULL 을 자동 제외하므로
     * 재시도 소진(next_retry_at IS NULL)건은 걸러진다.
     */
    List<RefundJpaEntity> findByStatusAndNextRetryAtLessThanEqual(String status, LocalDateTime now);

    List<RefundJpaEntity> findByStatusOrderByUpdatedAtDesc(String status);
}
