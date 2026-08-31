package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 적재 — 결제 행에 상품과 주문상태를 붙여 주는 용도다.
 *
 * <p>이 테이블만으로는 셀러의 주문 목록을 만들 수 없다. 주문 이벤트는 셀러를 싣지 않으므로
 * "누구 주문인지" 를 알 수 없고, 결제되지 않은 주문까지 섞인다. 소유의 근거는 언제나
 * {@code seller_sales} 다 — 이 테이블을 기준으로 목록을 만들면 <b>남의 주문이 보인다.</b>
 */
interface SellerOrderJpaRepository extends JpaRepository<SellerOrderJpaEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO seller.seller_orders
                (order_id, user_id, product_id, status, amount, ordered_at, updated_at)
            VALUES
                (:orderId, :userId, :productId, :status, :amount, :orderedAt, NOW())
            ON CONFLICT (order_id) DO UPDATE SET
                user_id    = EXCLUDED.user_id,
                product_id = COALESCE(EXCLUDED.product_id, seller.seller_orders.product_id),
                status     = EXCLUDED.status,
                amount     = EXCLUDED.amount,
                ordered_at = COALESCE(EXCLUDED.ordered_at, seller.seller_orders.ordered_at),
                updated_at = NOW()
            """, nativeQuery = true)
    void upsert(@Param("orderId") long orderId,
                @Param("userId") long userId,
                @Param("productId") Long productId,
                @Param("status") String status,
                @Param("amount") BigDecimal amount,
                @Param("orderedAt") LocalDateTime orderedAt);
}
