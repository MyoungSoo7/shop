package github.lms.lemuel.partner.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

/**
 * 환불 적재.
 *
 * <p>결제 행이 아직 없어도 그냥 넣는다 — FK 가 없는 이유가 이것이다. 순서를 보장하지 않는
 * 이상 "결제가 먼저 와 있을 것" 은 가정이 아니라 희망이다.
 */
interface PartnerRefundJpaRepository extends JpaRepository<PartnerRefundJpaEntity, PartnerRefundId> {

    @Modifying
    @Query(value = """
            INSERT INTO partner.partner_refunds
                (payment_id, refund_key, order_id, refund_amount, refunded_total, occurred_at)
            VALUES
                (:paymentId, :refundKey, :orderId, :refundAmount, :refundedTotal, NOW())
            ON CONFLICT (payment_id, refund_key) DO UPDATE SET
                order_id       = EXCLUDED.order_id,
                refund_amount  = EXCLUDED.refund_amount,
                refunded_total = EXCLUDED.refunded_total
            """, nativeQuery = true)
    void upsert(@Param("paymentId") long paymentId,
                @Param("refundKey") String refundKey,
                @Param("orderId") long orderId,
                @Param("refundAmount") BigDecimal refundAmount,
                @Param("refundedTotal") BigDecimal refundedTotal);
}
