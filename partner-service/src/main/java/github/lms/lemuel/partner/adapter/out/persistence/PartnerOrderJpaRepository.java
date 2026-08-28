package github.lms.lemuel.partner.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 적재 — 결제 행에 상품과 주문상태를 붙여 주는 용도다.
 *
 * <p>이 테이블만으로는 매출을 세지 않는다. 주문은 셀러를 싣지 않으므로 "누구 매출인지" 를
 * 알 수 없고, 결제되지 않은 주문까지 섞인다. 매출의 근거는 언제나 {@code partner_sales} 다.
 */
interface PartnerOrderJpaRepository extends JpaRepository<PartnerOrderJpaEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO partner.partner_orders
                (order_id, user_id, product_id, status, amount, ordered_at, updated_at)
            VALUES
                (:orderId, :userId, :productId, :status, :amount, :orderedAt, NOW())
            ON CONFLICT (order_id) DO UPDATE SET
                user_id    = EXCLUDED.user_id,
                product_id = COALESCE(EXCLUDED.product_id, partner.partner_orders.product_id),
                status     = EXCLUDED.status,
                amount     = EXCLUDED.amount,
                ordered_at = COALESCE(EXCLUDED.ordered_at, partner.partner_orders.ordered_at),
                updated_at = NOW()
            """, nativeQuery = true)
    void upsert(@Param("orderId") long orderId,
                @Param("userId") long userId,
                @Param("productId") Long productId,
                @Param("status") String status,
                @Param("amount") BigDecimal amount,
                @Param("orderedAt") LocalDateTime orderedAt);
}
