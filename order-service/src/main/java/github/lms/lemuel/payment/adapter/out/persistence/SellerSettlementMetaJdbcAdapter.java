package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * order-service 측 셀러 메타 해석 어댑터.
 *
 * <p>payments→orders→products 로 seller_id 를, users 로 seller_tier·settlement_cycle 을 해석한다.
 * order-service 가 소유한 opslab 테이블만 조회하므로 헥사고날·MSA 경계에 부합한다.
 * (settlement 의 SellerTierJdbcAdapter 가 하던 cross-service 조인을 발행 측으로 옮긴 것.)
 *
 * <p>상품 해석 경로는 두 갈래를 UNION 으로 합친다: 단건 주문은 orders.product_id,
 * 다건 주문(orders.product_id IS NULL)은 order_items 라인들의 product_id.
 * 같은 셀러의 상품 여러 개는 GROUP BY 로 한 행이 되므로, 결과가 2행 이상이면
 * 서로 다른 셀러(미할당 NULL 포함)가 섞인 주문이다 — payment 당 정산이 1건이라
 * 단일 셀러로 귀속할 수 없어 empty 를 반환한다(호출측이 발행을 생략).
 *
 * <p>products→users 는 LEFT JOIN — 셀러 미할당(seller_id NULL)이어도 seller_id=null 로 row 반환.
 * payment/order/product 체인 자체가 없으면 empty.
 */
@Repository
public class SellerSettlementMetaJdbcAdapter implements LoadSellerSettlementMetaPort {

    private static final Logger log = LoggerFactory.getLogger(SellerSettlementMetaJdbcAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public SellerSettlementMetaJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SellerSettlementMeta> findByPaymentId(Long paymentId) {
        String sql = """
                SELECT pr.seller_id       AS seller_id,
                       u.seller_tier      AS seller_tier,
                       u.settlement_cycle AS settlement_cycle
                FROM opslab.payments pay
                JOIN opslab.orders o ON o.id = pay.order_id
                JOIN opslab.products pr ON pr.id IN (
                    SELECT o.product_id WHERE o.product_id IS NOT NULL
                    UNION
                    SELECT oi.product_id FROM opslab.order_items oi WHERE oi.order_id = o.id
                )
                LEFT JOIN opslab.users u ON u.id = pr.seller_id
                WHERE pay.id = ?
                GROUP BY pr.seller_id, u.seller_tier, u.settlement_cycle
                """;
        List<SellerSettlementMeta> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            long sellerId = rs.getLong("seller_id");
            Long resolvedSellerId = rs.wasNull() ? null : sellerId;
            return new SellerSettlementMeta(
                    resolvedSellerId,
                    rs.getString("seller_tier"),
                    rs.getString("settlement_cycle"));
        }, paymentId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            log.info("셀러 혼재 주문 — 단일 셀러 귀속 불가로 셀러 메타 생략. paymentId={}, distinctSellers={}",
                    paymentId, rows.size());
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }
}
