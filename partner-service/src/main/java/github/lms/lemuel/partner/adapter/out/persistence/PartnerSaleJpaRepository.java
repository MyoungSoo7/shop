package github.lms.lemuel.partner.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 파트너 화면의 모든 숫자가 나오는 곳.
 *
 * <p><b>환불을 왜 매번 CTE 로 다시 집계하는가.</b> 결제 행에 환불 누계 컬럼을 두고 갱신하면
 * 조회가 훨씬 싸다. 그렇게 하지 않은 이유는 순서다 — {@code payment.refunded} 가
 * {@code payment.captured} 보다 먼저 도착할 수 있다(다른 토픽, 다른 파티션). 결제 행이 아직
 * 없는데 누계를 더하러 가면 그 환불은 갈 곳이 없어 사라지고, 그 손실은 화면에 "매출이 조금 큰"
 * 형태로만 나타나 아무도 눈치채지 못한다. 별도 테이블에 먼저 쌓아 두고 조회 시점에 붙이면
 * 도착 순서가 무의미해진다.
 *
 * <p><b>{@code GREATEST(MAX(refunded_total), SUM(refund_amount))} 의 이유.</b> 계약상
 * {@code payment.refunded} 는 이번 환불액({@code refundAmount})과 누계({@code refundedTotal})를
 * 둘 다 실을 수 있고 <b>둘 다 선택 필드</b>다. 누계만 오면 SUM 은 과소, 건별만 오면 MAX 는
 * null 이다. 큰 쪽을 취하면 어느 쪽이 와도 실제 환불액 이상으로는 부풀지 않는다.
 */
interface PartnerSaleJpaRepository extends JpaRepository<PartnerSaleJpaEntity, Long> {

    /**
     * 결제 적재. {@code WHERE} 절이 붙은 이유는 {@code captured_at} 추정 때문이다 —
     * 이벤트에 {@code capturedAt} 이 없어 수신시각으로 대체한 행이 이미 있고 나중에 정확한
     * 값이 오면 갱신해야 하지만, 그 반대(정확한 값을 추정치로 덮기)는 막아야 한다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO partner.partner_sales
                (payment_id, order_id, seller_id, amount, seller_tier, settlement_cycle,
                 payment_method, captured_at, sale_date, captured_at_estimated, updated_at)
            VALUES
                (:paymentId, :orderId, :sellerId, :amount, :sellerTier, :settlementCycle,
                 :paymentMethod, :capturedAt, CAST(CAST(:capturedAt AS TIMESTAMP) AS DATE),
                 :capturedAtEstimated, NOW())
            ON CONFLICT (payment_id) DO UPDATE SET
                order_id             = EXCLUDED.order_id,
                seller_id            = EXCLUDED.seller_id,
                amount               = EXCLUDED.amount,
                seller_tier          = EXCLUDED.seller_tier,
                settlement_cycle     = EXCLUDED.settlement_cycle,
                payment_method       = EXCLUDED.payment_method,
                captured_at          = EXCLUDED.captured_at,
                sale_date            = EXCLUDED.sale_date,
                captured_at_estimated = EXCLUDED.captured_at_estimated,
                updated_at           = NOW()
            WHERE partner.partner_sales.captured_at_estimated = TRUE
               OR EXCLUDED.captured_at_estimated = FALSE
            """, nativeQuery = true)
    void upsert(@Param("paymentId") long paymentId,
                @Param("orderId") long orderId,
                @Param("sellerId") Long sellerId,
                @Param("amount") BigDecimal amount,
                @Param("sellerTier") String sellerTier,
                @Param("settlementCycle") String settlementCycle,
                @Param("paymentMethod") String paymentMethod,
                @Param("capturedAt") LocalDateTime capturedAt,
                @Param("capturedAtEstimated") boolean capturedAtEstimated);

    /** 반환 순서: gross, refunded, net, orderCount */
    @Query(value = """
            WITH sale AS (
                SELECT s.payment_id, s.order_id, s.amount
                  FROM partner.partner_sales s
                 WHERE s.seller_id = :sellerId
                   AND s.sale_date BETWEEN :fromDate AND :toDate
            ), refund AS (
                SELECT r.payment_id,
                       GREATEST(COALESCE(MAX(r.refunded_total), 0),
                                COALESCE(SUM(r.refund_amount), 0)) AS refunded
                  FROM partner.partner_refunds r
                 WHERE r.payment_id IN (SELECT payment_id FROM sale)
                 GROUP BY r.payment_id
            )
            SELECT COALESCE(SUM(s.amount), 0),
                   COALESCE(SUM(COALESCE(f.refunded, 0)), 0),
                   COALESCE(SUM(s.amount - COALESCE(f.refunded, 0)), 0),
                   COUNT(DISTINCT s.order_id)
              FROM sale s
              LEFT JOIN refund f ON f.payment_id = s.payment_id
            """, nativeQuery = true)
    List<Object[]> summaryRows(@Param("sellerId") long sellerId,
                        @Param("fromDate") LocalDate fromDate,
                        @Param("toDate") LocalDate toDate);

    /** 반환 순서: sale_date, gross, refunded, net, orderCount */
    @Query(value = """
            WITH sale AS (
                SELECT s.payment_id, s.order_id, s.amount, s.sale_date
                  FROM partner.partner_sales s
                 WHERE s.seller_id = :sellerId
                   AND s.sale_date BETWEEN :fromDate AND :toDate
            ), refund AS (
                SELECT r.payment_id,
                       GREATEST(COALESCE(MAX(r.refunded_total), 0),
                                COALESCE(SUM(r.refund_amount), 0)) AS refunded
                  FROM partner.partner_refunds r
                 WHERE r.payment_id IN (SELECT payment_id FROM sale)
                 GROUP BY r.payment_id
            )
            SELECT s.sale_date,
                   COALESCE(SUM(s.amount), 0),
                   COALESCE(SUM(COALESCE(f.refunded, 0)), 0),
                   COALESCE(SUM(s.amount - COALESCE(f.refunded, 0)), 0),
                   COUNT(DISTINCT s.order_id)
              FROM sale s
              LEFT JOIN refund f ON f.payment_id = s.payment_id
             GROUP BY s.sale_date
             ORDER BY s.sale_date
            """, nativeQuery = true)
    List<Object[]> dailyRows(@Param("sellerId") long sellerId,
                             @Param("fromDate") LocalDate fromDate,
                             @Param("toDate") LocalDate toDate);

    /**
     * 반환 순서: product_id, product_name, net, orderCount
     *
     * <p>{@code partner_orders} 를 LEFT JOIN 하는 것은 결제 이벤트가 상품을 싣지 않기
     * 때문이다. 주문 이벤트가 아직 안 왔으면 {@code product_id} 가 null 인 행으로 남고, 그
     * 행을 버리지 않는 이유는 버리는 순간 상품별 합이 총매출과 어긋나서다.
     */
    @Query(value = """
            WITH sale AS (
                SELECT s.payment_id, s.order_id, s.amount
                  FROM partner.partner_sales s
                 WHERE s.seller_id = :sellerId
                   AND s.sale_date BETWEEN :fromDate AND :toDate
            ), refund AS (
                SELECT r.payment_id,
                       GREATEST(COALESCE(MAX(r.refunded_total), 0),
                                COALESCE(SUM(r.refund_amount), 0)) AS refunded
                  FROM partner.partner_refunds r
                 WHERE r.payment_id IN (SELECT payment_id FROM sale)
                 GROUP BY r.payment_id
            )
            SELECT o.product_id,
                   pr.name,
                   COALESCE(SUM(s.amount - COALESCE(f.refunded, 0)), 0) AS net_amount,
                   COUNT(DISTINCT s.order_id)
              FROM sale s
              LEFT JOIN refund f              ON f.payment_id = s.payment_id
              LEFT JOIN partner.partner_orders o  ON o.order_id = s.order_id
              LEFT JOIN partner.partner_products pr ON pr.product_id = o.product_id
             GROUP BY o.product_id, pr.name
             ORDER BY net_amount DESC, o.product_id
             LIMIT :maxRows
            """, nativeQuery = true)
    List<Object[]> bestProductRows(@Param("sellerId") long sellerId,
                                   @Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate,
                                   @Param("maxRows") int maxRows);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM partner.partner_sales s
                 WHERE s.seller_id = :sellerId
                   AND s.sale_date BETWEEN :fromDate AND :toDate
                   AND s.captured_at_estimated = TRUE
            )
            """, nativeQuery = true)
    boolean hasEstimatedCaptureDates(@Param("sellerId") long sellerId,
                                     @Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate);

    /**
     * {@code CAST(:orderId AS BIGINT)} 로 감싼 것은 PostgreSQL 이 파라미터의 타입을 추론하지
     * 못해 {@code could not determine data type of parameter} 로 거절하기 때문이다 —
     * {@code :orderId IS NULL} 은 양쪽 다 미지 타입이라 추론할 근거가 없다.
     */
    @Query(value = """
            SELECT COUNT(*)
              FROM partner.partner_sales s
             WHERE s.seller_id = :sellerId
               AND s.sale_date BETWEEN :fromDate AND :toDate
               AND (CAST(:orderId AS BIGINT) IS NULL OR s.order_id = CAST(:orderId AS BIGINT))
            """, nativeQuery = true)
    long countOrders(@Param("sellerId") long sellerId,
                     @Param("fromDate") LocalDate fromDate,
                     @Param("toDate") LocalDate toDate,
                     @Param("orderId") Long orderId);

    /**
     * 반환 순서: order_id, payment_id, captured_at, captured_at_estimated, amount,
     * refunded, payment_method, order_status, product_id, product_name
     *
     * <p>정렬 tiebreaker 로 payment_id 를 붙인 것은 {@code captured_at} 이 같은 결제가 실제로
     * 생기기 때문이다(추정치로 채운 행들은 초 단위까지 같다). 정렬이 불안정하면 2페이지에
     * 1페이지와 같은 행이 다시 나오거나 어떤 행은 영영 안 보인다.
     */
    @Query(value = """
            WITH sale AS (
                SELECT s.payment_id, s.order_id, s.amount, s.captured_at,
                       s.captured_at_estimated, s.payment_method
                  FROM partner.partner_sales s
                 WHERE s.seller_id = :sellerId
                   AND s.sale_date BETWEEN :fromDate AND :toDate
                   AND (CAST(:orderId AS BIGINT) IS NULL
                        OR s.order_id = CAST(:orderId AS BIGINT))
                 ORDER BY s.captured_at DESC, s.payment_id DESC
                 LIMIT :maxRows OFFSET :skip
            ), refund AS (
                SELECT r.payment_id,
                       GREATEST(COALESCE(MAX(r.refunded_total), 0),
                                COALESCE(SUM(r.refund_amount), 0)) AS refunded
                  FROM partner.partner_refunds r
                 WHERE r.payment_id IN (SELECT payment_id FROM sale)
                 GROUP BY r.payment_id
            )
            SELECT s.order_id, s.payment_id, s.captured_at, s.captured_at_estimated, s.amount,
                   COALESCE(f.refunded, 0), s.payment_method, o.status, o.product_id, pr.name
              FROM sale s
              LEFT JOIN refund f                  ON f.payment_id = s.payment_id
              LEFT JOIN partner.partner_orders o    ON o.order_id = s.order_id
              LEFT JOIN partner.partner_products pr ON pr.product_id = o.product_id
             ORDER BY s.captured_at DESC, s.payment_id DESC
            """, nativeQuery = true)
    List<Object[]> orderRows(@Param("sellerId") long sellerId,
                             @Param("fromDate") LocalDate fromDate,
                             @Param("toDate") LocalDate toDate,
                             @Param("orderId") Long orderId,
                             @Param("maxRows") int maxRows,
                             @Param("skip") long skip);
}
