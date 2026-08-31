package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 셀러 주문 화면이 서 있는 곳.
 *
 * <p><b>왜 주문 목록의 기준이 주문이 아니라 결제인가.</b> {@code order.created} 에는 셀러가
 * 실려 있지 않다(ADR 0020). 셀러 소유를 말해 주는 이벤트는 {@code payment.captured} 뿐이므로,
 * 결제 행을 기준으로 잡고 주문·상품·송장을 LEFT JOIN 으로 붙인다. 그 대가로 <b>결제 전 주문은
 * 이 화면에 없다.</b> 출고 대상은 대체로 결제된 주문이니 실무적으로는 맞지만, "맞아서" 가
 * 아니라 "그것밖에 없어서" 라는 걸 남긴다.
 *
 * <p><b>환불을 매번 CTE 로 다시 집계하는 이유</b>는 파트너 콘솔과 같다 — {@code payment.refunded}
 * 가 {@code payment.captured} 보다 먼저 도착할 수 있어서, 결제 행에 누계를 더하러 가면 그 환불이
 * 갈 곳이 없어 사라진다. {@code GREATEST(MAX(refunded_total), SUM(refund_amount))} 인 것은
 * 계약상 두 필드가 <b>둘 다 선택</b>이기 때문이고, 큰 쪽을 취해야 어느 쪽이 와도 부풀지 않는다.
 */
interface SellerSaleJpaRepository extends JpaRepository<SellerSaleJpaEntity, Long> {

    /**
     * 결제 적재. {@code WHERE} 절이 붙은 이유는 {@code captured_at} 추정 때문이다 —
     * 이벤트에 {@code capturedAt} 이 없어 수신시각으로 대체한 행이 이미 있고 나중에 정확한
     * 값이 오면 갱신해야 하지만, 그 반대(정확한 값을 추정치로 덮기)는 막아야 한다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO seller.seller_sales
                (payment_id, order_id, seller_id, amount, payment_method,
                 captured_at, sale_date, captured_at_estimated, updated_at)
            VALUES
                (:paymentId, :orderId, :sellerId, :amount, :paymentMethod,
                 :capturedAt, CAST(CAST(:capturedAt AS TIMESTAMP) AS DATE),
                 :capturedAtEstimated, NOW())
            ON CONFLICT (payment_id) DO UPDATE SET
                order_id              = EXCLUDED.order_id,
                seller_id             = EXCLUDED.seller_id,
                amount                = EXCLUDED.amount,
                payment_method        = EXCLUDED.payment_method,
                captured_at           = EXCLUDED.captured_at,
                sale_date             = EXCLUDED.sale_date,
                captured_at_estimated = EXCLUDED.captured_at_estimated,
                updated_at            = NOW()
            WHERE seller.seller_sales.captured_at_estimated = TRUE
               OR EXCLUDED.captured_at_estimated = FALSE
            """, nativeQuery = true)
    void upsert(@Param("paymentId") long paymentId,
                @Param("orderId") long orderId,
                @Param("sellerId") Long sellerId,
                @Param("amount") BigDecimal amount,
                @Param("paymentMethod") String paymentMethod,
                @Param("capturedAt") LocalDateTime capturedAt,
                @Param("capturedAtEstimated") boolean capturedAtEstimated);

    /**
     * {@code CAST(:orderId AS BIGINT)} 로 감싼 것은 PostgreSQL 이 파라미터의 타입을 추론하지
     * 못해 {@code could not determine data type of parameter} 로 거절하기 때문이다 —
     * {@code :orderId IS NULL} 은 양쪽 다 미지 타입이라 추론할 근거가 없다.
     * {@code :unshippedOnly} 도 같은 이유로 감쌌다.
     *
     * <p>"미출고" 의 정의는 <b>송장 요청 행이 없는 것</b>이다. order-service 의 배송 상태를
     * 여기서 다시 판단하지 않는다 — 그 원장은 저쪽에 있고, 이쪽이 아는 사실은 "우리가 출고를
     * 요청했는가" 뿐이다. 화면 문구도 그렇게 적는다.
     */
    @Query(value = """
            SELECT COUNT(*)
              FROM seller.seller_sales s
             WHERE s.seller_id = :sellerId
               AND s.sale_date BETWEEN :fromDate AND :toDate
               AND (CAST(:orderId AS BIGINT) IS NULL OR s.order_id = CAST(:orderId AS BIGINT))
               AND (CAST(:unshippedOnly AS BOOLEAN) = FALSE
                    OR NOT EXISTS (SELECT 1
                                     FROM seller.seller_shipment_requests r
                                    WHERE r.order_id = s.order_id))
            """, nativeQuery = true)
    long countOrders(@Param("sellerId") long sellerId,
                     @Param("fromDate") LocalDate fromDate,
                     @Param("toDate") LocalDate toDate,
                     @Param("orderId") Long orderId,
                     @Param("unshippedOnly") boolean unshippedOnly);

    /**
     * 반환 순서: order_id, payment_id, captured_at, captured_at_estimated, amount,
     * refunded, payment_method, order_status, product_id, product_name,
     * carrier, tracking_number, requested_at
     *
     * <p>정렬 tiebreaker 로 payment_id 를 붙인 것은 {@code captured_at} 이 같은 결제가 실제로
     * 생기기 때문이다(추정치로 채운 행들은 초 단위까지 같다). 정렬이 불안정하면 2페이지에
     * 1페이지와 같은 행이 다시 나오거나 어떤 행은 영영 안 보인다 — 출고 목록에서 그건
     * <b>주문 하나가 통째로 누락되는</b> 것이다.
     *
     * <p>송장은 {@code seller_shipment_requests} 를 LEFT JOIN 해서 붙인다. INNER 로 바꾸면
     * 미출고 주문이 사라지는데, 그게 정확히 이 화면이 보여야 할 것이다.
     */
    @Query(value = """
            WITH sale AS (
                SELECT s.payment_id, s.order_id, s.amount, s.captured_at,
                       s.captured_at_estimated, s.payment_method
                  FROM seller.seller_sales s
                 WHERE s.seller_id = :sellerId
                   AND s.sale_date BETWEEN :fromDate AND :toDate
                   AND (CAST(:orderId AS BIGINT) IS NULL
                        OR s.order_id = CAST(:orderId AS BIGINT))
                   AND (CAST(:unshippedOnly AS BOOLEAN) = FALSE
                        OR NOT EXISTS (SELECT 1
                                         FROM seller.seller_shipment_requests r
                                        WHERE r.order_id = s.order_id))
                 ORDER BY s.captured_at DESC, s.payment_id DESC
                 LIMIT :maxRows OFFSET :skip
            ), refund AS (
                SELECT r.payment_id,
                       GREATEST(COALESCE(MAX(r.refunded_total), 0),
                                COALESCE(SUM(r.refund_amount), 0)) AS refunded
                  FROM seller.seller_refunds r
                 WHERE r.payment_id IN (SELECT payment_id FROM sale)
                 GROUP BY r.payment_id
            )
            SELECT s.order_id, s.payment_id, s.captured_at, s.captured_at_estimated, s.amount,
                   COALESCE(f.refunded, 0), s.payment_method, o.status, o.product_id, pr.name,
                   sr.carrier, sr.tracking_number, sr.requested_at
              FROM sale s
              LEFT JOIN refund f                              ON f.payment_id = s.payment_id
              LEFT JOIN seller.seller_orders o                ON o.order_id = s.order_id
              LEFT JOIN seller.seller_products pr             ON pr.product_id = o.product_id
              LEFT JOIN seller.seller_shipment_requests sr    ON sr.order_id = s.order_id
             ORDER BY s.captured_at DESC, s.payment_id DESC
            """, nativeQuery = true)
    List<Object[]> orderRows(@Param("sellerId") long sellerId,
                             @Param("fromDate") LocalDate fromDate,
                             @Param("toDate") LocalDate toDate,
                             @Param("orderId") Long orderId,
                             @Param("unshippedOnly") boolean unshippedOnly,
                             @Param("maxRows") int maxRows,
                             @Param("skip") long skip);
}
