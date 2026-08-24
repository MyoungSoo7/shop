package github.lms.lemuel.payment.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for PaymentJpaEntity
 * PaymentRepository와 통합됨
 */
@Repository
public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {

    Optional<PaymentJpaEntity> findByOrderId(Long orderId);

    /**
     * 환불용 비관적 락 조회 (SELECT ... FOR UPDATE).
     * 동시 환불(전액/부분/분할)이 같은 결제 행을 읽고 각자 refundedAmount 를 갱신해
     * lost update 가 나거나 PG 가 이중 호출되는 것을 막는다. 부모 결제 행을 잠그면
     * 같은 결제의 자식 tender 갱신도 트랜잭션 단위로 직렬화된다.
     * 반드시 @Transactional 컨텍스트 안에서 호출할 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.id = :id")
    Optional<PaymentJpaEntity> findByIdForUpdate(@Param("id") Long id);

    List<PaymentJpaEntity> findByStatus(String status);

    /**
     * 입금 대기(READY)로 cutoff 이전에 생성된 결제 — 미입금 만료 배치의 구동 쿼리.
     * 부분 인덱스 {@code idx_payments_pending_expiry (created_at) WHERE status='READY'} 가 커버한다.
     */
    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.status = 'READY' AND p.createdAt < :cutoff ORDER BY p.createdAt ASC")
    List<PaymentJpaEntity> findPendingCreatedBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 상태별 건수 — settlement 프로젝션 cross-DB 대사(ADR 0020 Phase 5.2)의 원천 카운트. */
    long countByStatus(String status);

    /** CAPTURED 결제 금액 합계 — cross-DB 금액 대사(Phase 5.2)의 원천. 환불(REFUNDED)은 제외해 프로젝션과 정합. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentJpaEntity p WHERE p.status = 'CAPTURED'")
    java.math.BigDecimal sumCapturedAmount();

    /**
     * 특정 기간 동안 캡처된 결제 조회 (정산 생성용)
     * @param startDateTime 시작 시간
     * @param endDateTime 종료 시간
     * @param status 결제 상태
     * @return 캡처된 결제 목록
     */
    List<PaymentJpaEntity> findByCapturedAtBetweenAndStatus(
            LocalDateTime startDateTime,
            LocalDateTime endDateTime,
            String status);

    /**
     * JPQL 쿼리로 캡처된 결제 조회
     * @param startDate 시작 시간
     * @param endDate 종료 시간
     * @return 캡처된 결제 목록
     */
    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.status = 'CAPTURED' AND p.updatedAt >= :startDate AND p.updatedAt < :endDate")
    List<PaymentJpaEntity> findCapturedPaymentsBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    /**
     * Spring Batch용 페이징 쿼리
     * @param startDate 시작 시간
     * @param endDate 종료 시간
     * @param status 상태
     * @param pageable 페이징 정보
     * @return 페이징된 결제 목록
     */
    Page<PaymentJpaEntity> findByCapturedAtBetweenAndStatus(
            LocalDateTime startDate,
            LocalDateTime endDate,
            String status,
            Pageable pageable
    );
}
