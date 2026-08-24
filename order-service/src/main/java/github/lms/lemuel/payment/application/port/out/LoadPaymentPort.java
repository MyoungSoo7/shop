package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.PaymentDomain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoadPaymentPort {
    Optional<PaymentDomain> loadById(Long id);

    /** CAPTURED 상태 결제 전체 — settlement 프로젝션 백필(ADR 0020 Phase 4 Chunk 3) 전용. */
    List<PaymentDomain> findAllCaptured();

    /**
     * 환불 동시성 제어용 비관적 락 조회.
     * 트랜잭션 종료 시까지 결제 행을 잠가 동시 환불의 lost update / PG 이중 호출을 방지한다.
     */
    Optional<PaymentDomain> loadByIdForUpdate(Long id);

    Optional<PaymentDomain> loadByOrderId(Long orderId);

    /**
     * 입금 대기(READY)로 {@code cutoff} 이전에 생성된 결제 — 미입금 만료 배치 전용.
     *
     * <p>수단 필터는 여기서 하지 않는다 — 만료 대상 수단 판정은
     * {@code PaymentExpiryPolicy} 가 도메인 규칙으로 책임진다. 오래된 순으로 최대 {@code limit} 건.
     */
    List<PaymentDomain> findPendingCreatedBefore(LocalDateTime cutoff, int limit);
}
