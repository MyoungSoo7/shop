package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.LoadProductShippingChargePort;
import github.lms.lemuel.shipping.domain.ShippingChargeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품 배송비 속성 조회 어댑터.
 *
 * <p>product 도메인의 JPA 엔티티/리포지토리를 import 하지 않는다(ArchUnit 규칙: 어댑터는 타 도메인
 * 영속 계층을 직접 참조하지 않는다). payment 의 {@code SellerSettlementMetaJdbcAdapter} 와 같은
 * 방식으로 읽기 전용 SQL 만 사용한다.
 *
 * <p><b>스키마 한정 필수</b> — order-service 의 JPA 는 {@code default_schema=opslab} 로 도는 반면
 * JdbcTemplate 은 {@code search_path} 를 따른다. 한정하지 않으면 로컬에서만 통하고 배포 후 터진다.
 */
@Repository
public class ProductShippingChargeJdbcAdapter implements LoadProductShippingChargePort {

    private static final Logger log = LoggerFactory.getLogger(ProductShippingChargeJdbcAdapter.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProductShippingChargeJdbcAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, ProductShippingCharge> loadByProductIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT p.id                   AS product_id,
                       p.seller_id            AS seller_id,
                       p.shipping_charge_type AS shipping_charge_type,
                       p.shipping_charge_fee  AS shipping_charge_fee
                  FROM opslab.products p
                 WHERE p.id IN (:productIds)
                """;
        List<ProductShippingCharge> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource("productIds", productIds),
                (rs, rowNum) -> {
                    long sellerId = rs.getLong("seller_id");
                    Long resolvedSellerId = rs.wasNull() ? null : sellerId;
                    return new ProductShippingCharge(
                            rs.getLong("product_id"),
                            resolvedSellerId,
                            parseType(rs.getString("shipping_charge_type"), rs.getLong("product_id")),
                            rs.getBigDecimal("shipping_charge_fee"));
                });

        Map<Long, ProductShippingCharge> result = new HashMap<>(rows.size());
        for (ProductShippingCharge row : rows) {
            result.put(row.productId(), row);
        }
        return result;
    }

    /**
     * 알 수 없는 유형 문자열은 FREE 로 착지시킨다. DB CHECK 제약이 1 차 방어선이지만, 제약을 우회한
     * 수기 수정이 있었을 때 배송비를 임의로 부과하는 것보다 부과하지 않는 쪽이 안전하다.
     */
    private ShippingChargeType parseType(String raw, long productId) {
        if (raw == null) {
            return ShippingChargeType.FREE;
        }
        try {
            return ShippingChargeType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 배송비 유형 — FREE 로 처리한다: productId={}, type={}", productId, raw);
            return ShippingChargeType.FREE;
        }
    }
}
