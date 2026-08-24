package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA Repository for Order
 */
public interface SpringDataOrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    List<OrderJpaEntity> findByUserId(Long userId);

    /**
     * 사용자 주문 목록 (선택적 status/기간 필터).
     *
     * <p>null-가드 파라미터에는 반드시 {@code CAST(:param AS ...)} 를 준다. PostgreSQL 은
     * {@code $n IS NULL} 형태로만 등장하는 bind 파라미터의 타입을 추론하지 못해
     * {@code SQLState 42P18 "could not determine data type of parameter"} 로 쿼리 전체가
     * 실패한다(프론트가 status/from/to 를 모두 null 로 호출하는 주문내역 조회에서 재현).
     * 명시적 CAST 로 타입을 고정해 우회한다.
     */
    @Query("""
            SELECT o FROM OrderJpaEntity o
            WHERE o.userId = :userId
              AND (CAST(:status AS string) IS NULL OR o.status = :status)
              AND (CAST(:from AS LocalDateTime) IS NULL OR o.createdAt >= :from)
              AND (CAST(:to AS LocalDateTime) IS NULL OR o.createdAt < :to)
            ORDER BY o.createdAt DESC
            """)
    List<OrderJpaEntity> findUserOrders(@Param("userId") Long userId,
                                        @Param("status") String status,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    /**
     * 회수 대기 후보 — 배송된 물건에 환불·취소가 끝났는데 재고가 아직 돌아오지 않은 주문.
     *
     * <p>인덱스로 좁힐 수 있는 조건까지만 거른다. 최종 판정(라인 존재 포함)은 도메인이 하므로
     * 여기서 라인을 조인하지 않는다. 부분 인덱스 {@code idx_orders_awaiting_stock_reclaim} 가 커버.
     */
    @Query("""
            SELECT o FROM OrderJpaEntity o
            WHERE o.shipped = true
              AND o.stockRestored = false
              AND o.status IN ('REFUNDED', 'CANCELED')
            ORDER BY o.updatedAt ASC
            """)
    List<OrderJpaEntity> findAwaitingStockReclaim(Pageable pageable);

    /**
     * 회수 지연 임계를 갓 넘긴 구간의 대기 건 — 지연 신호 발행 전용.
     * 같은 부분 인덱스가 커버하며, 구간이 좁아 매 주기 스캔 비용이 일정하다.
     */
    @Query("""
            SELECT o FROM OrderJpaEntity o
            WHERE o.shipped = true
              AND o.stockRestored = false
              AND o.status IN ('REFUNDED', 'CANCELED')
              AND o.updatedAt > :from
              AND o.updatedAt <= :to
            ORDER BY o.updatedAt ASC
            """)
    List<OrderJpaEntity> findStockReclaimCrossedBetween(@Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to,
                                                        Pageable pageable);

    /** 전체 주문 금액 합계 — cross-DB 금액 대사(ADR 0020 Phase 5.2)의 원천. */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM OrderJpaEntity o")
    java.math.BigDecimal sumAmount();
}
