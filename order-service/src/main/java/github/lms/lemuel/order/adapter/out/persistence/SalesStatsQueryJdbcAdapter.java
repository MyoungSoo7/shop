package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadSalesStatsPort;
import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.ProductSales;
import github.lms.lemuel.order.domain.SalesTotal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 판매 통계 조회 어댑터 — 집계라 JPA 를 거치지 않고 SQL 로 직접 읽는다.
 *
 * <p>규약은 {@link OrderPersistenceAdapter} 와 같다.
 * <ul>
 *   <li><b>{@code opslab.} 한정</b> — Hibernate 의 {@code default_schema} 는 JPA 경로에만 적용되고
 *       JdbcTemplate 은 커넥션의 {@code search_path} 를 따른다. 한정하지 않으면 컴파일도 단위
 *       테스트도 통과한 채 배포 후에 "relation does not exist" 로 터진다.</li>
 *   <li><b>{@code (? IS NULL OR col = ?)} 를 쓰지 않는다</b> — PostgreSQL 이 그 자리의 파라미터
 *       타입을 추론하지 못해({@code 42P18}) 쿼리 전체가 실행 시점에 터진다. 조립되는 것은 상수
 *       조각뿐이고 사용자 입력은 전부 바인딩이다.</li>
 * </ul>
 *
 * <h2>세 질의가 같은 FROM·WHERE 조각을 공유한다</h2>
 * 공유하지 않으면 어느 하나만 조건이 바뀌어도 셋이 서로 다른 모집단을 세게 되고, 그 불일치는
 * 화면에서 "상위 20개의 합이 전체의 130%" 같은 형태로만 드러난다. 어느 쪽이 틀렸는지는 알려
 * 주지 않는다.
 *
 * <h2>순액을 쓰는 이유</h2>
 * 판매액은 {@code line_amount} 가 아니라 {@code line_amount - allocated_discount} 다. 앞의 것은
 * 할인 전 금액이라 쿠폰 할인분까지 매출로 잡는다(V20260825210000 이 안분 컬럼을 만든 이유이기도
 * 하다 — 환불 단위가 곧 이 순액이다). 취소된 라인({@code canceled_at IS NOT NULL})은 아예 빼는데,
 * 부분 취소가 일어난 주문은 주문 상태가 여전히 결제 상태로 남기 때문에 <b>상태만 걸러서는
 * 걸러지지 않는다</b>.
 */
@Repository
@RequiredArgsConstructor
public class SalesStatsQueryJdbcAdapter implements LoadSalesStatsPort {

    /**
     * 살아 있는 라인과 그 주문. 세 질의가 공유한다.
     *
     * <p>{@code JOIN orders} 가 필요한 이유는 상태·기간 조건이 주문 쪽에 있기 때문이다. 라인만 보고
     * 기간을 자르면 {@code order_items.created_at} 을 쓰게 되는데, 그 값은 라인이 <b>추가된</b>
     * 시각이라 주문 시각과 같다는 보장이 없다.
     */
    private static final String FROM_LIVE_LINES = """
             FROM opslab.order_items i
             JOIN opslab.orders o ON o.id = i.order_id""";

    /** 라인 순액 합계. 쿠폰 안분액을 뺀 "이 라인이 실제로 받은 돈". */
    private static final String NET_SUM = "COALESCE(SUM(i.line_amount - i.allocated_discount), 0)";

    private static final RowMapper<ProductSales> PRODUCT_MAPPER = (rs, rowNum) -> new ProductSales(
            rs.getLong("product_id"),
            rs.getString("product_name"),
            rs.getLong("quantity"),
            rs.getBigDecimal("net_amount"),
            rs.getLong("order_count"));

    /**
     * 미분류 줄은 {@code category_id} 가 NULL 로 온다. {@code getLong} 은 NULL 에 0 을 돌려주므로
     * {@code wasNull()} 로 구분하지 않으면 미분류가 "id 0번 카테고리"로 둔갑한다.
     */
    private static final RowMapper<CategorySales> CATEGORY_MAPPER = (rs, rowNum) -> {
        long categoryId = rs.getLong("category_id");
        boolean unclassified = rs.wasNull();
        int depth = rs.getInt("depth");
        boolean depthNull = rs.wasNull();
        return new CategorySales(
                unclassified ? null : categoryId,
                rs.getString("category_name"),
                rs.getString("path_slug"),
                depthNull ? null : depth,
                rs.getLong("quantity"),
                rs.getBigDecimal("net_amount"),
                rs.getLong("order_count"));
    };

    private final JdbcTemplate jdbcTemplate;

    /**
     * 상위 상품.
     *
     * <p>상품명을 {@code MAX(product_name)} 으로 뽑지 않는다. 그건 사전순 최대값이라 "가장 최근
     * 이름"과 아무 관계가 없고, 기간을 하루 넓혔을 뿐인데 표시 이름이 바뀐다. 기간 안에서 가장
     * 최근 라인의 스냅샷을 고른다.
     */
    @Override
    public List<ProductSales> topProducts(SalesCriteria criteria, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);
        args.add(limit);

        String sql = """
                SELECT i.product_id AS product_id,
                       (ARRAY_AGG(i.product_name ORDER BY i.created_at DESC, i.id DESC))[1] AS product_name,
                       SUM(i.quantity) AS quantity,
                       %s AS net_amount,
                       COUNT(DISTINCT i.order_id) AS order_count
                %s
                %s
                 GROUP BY i.product_id
                 ORDER BY net_amount DESC, quantity DESC, i.product_id ASC
                 LIMIT ?""".formatted(NET_SUM, FROM_LIVE_LINES, where);

        return jdbcTemplate.query(sql, PRODUCT_MAPPER, args.toArray());
    }

    /**
     * 카테고리별 분포.
     *
     * <p><b>{@code LEFT JOIN … AND pc.is_primary} 가 이 질의의 전부다.</b>
     * <ul>
     *   <li>{@code is_primary} 조건을 ON 절에 두면 대표 분류 한 행만 붙는다 — 상품당 최대 1 행을
     *       부분 유니크 인덱스({@code uq_product_primary_category})가 강제하므로, M:N 이 만드는
     *       중복 계산이 원천에서 사라진다.</li>
     *   <li>{@code LEFT} 라서 대표 분류가 없는 상품도 <b>남는다</b>. 이 조건을 WHERE 로 옮기는
     *       순간 그 라인들은 결과에서 통째로 사라지고, 카테고리 합계가 전체 매출보다 조용히
     *       작아진다. 그 상품들은 {@code category_id IS NULL} 한 줄(미분류)로 모인다.</li>
     * </ul>
     *
     * <p>소프트 삭제된 분류({@code deleted_at IS NOT NULL})도 그대로 보여 준다. 지난 판매는 그때의
     * 분류로 일어난 사실이고, 분류를 지웠다고 그 매출이 미분류가 되는 것은 사실을 고치는 것이다.
     */
    @Override
    public List<CategorySales> byCategory(SalesCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        String sql = """
                SELECT c.id AS category_id,
                       c.name AS category_name,
                       c.path_slug AS path_slug,
                       c.depth AS depth,
                       SUM(i.quantity) AS quantity,
                       %s AS net_amount,
                       COUNT(DISTINCT i.order_id) AS order_count
                %s
                 LEFT JOIN opslab.product_ecommerce_categories pc
                        ON pc.product_id = i.product_id AND pc.is_primary
                 LEFT JOIN opslab.ecommerce_categories c ON c.id = pc.category_id
                %s
                 GROUP BY c.id, c.name, c.path_slug, c.depth
                 ORDER BY net_amount DESC, quantity DESC, c.id ASC NULLS LAST"""
                .formatted(NET_SUM, FROM_LIVE_LINES, where);

        return jdbcTemplate.query(sql, CATEGORY_MAPPER, args.toArray());
    }

    /**
     * 잘라내기 없는 전 범위 합계.
     *
     * <p>{@code COALESCE} 로 감싸는 이유는 {@code countByStatus} 와 같다 — 한 건도 없을 때
     * {@code SUM} 은 0 이 아니라 NULL 을 준다. 그대로 내보내면 화면이 "0원"이 아니라 빈 칸을 그리고,
     * 운영자는 판매가 없었던 것인지 조회가 실패한 것인지 구분하지 못한다.
     */
    @Override
    public SalesTotal total(SalesCriteria criteria) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(criteria, args);

        String sql = """
                SELECT COALESCE(SUM(i.quantity), 0) AS quantity,
                       %s AS net_amount,
                       COUNT(*) AS line_count,
                       COUNT(DISTINCT i.order_id) AS order_count
                %s
                %s""".formatted(NET_SUM, FROM_LIVE_LINES, where);

        SalesTotal total = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new SalesTotal(
                rs.getLong("quantity"),
                rs.getBigDecimal("net_amount"),
                rs.getLong("line_count"),
                rs.getLong("order_count")), args.toArray());

        return total == null ? SalesTotal.empty() : total;
    }

    /**
     * 값이 있는 조건만 WHERE 절로 조립하고 같은 순서로 바인딩 인자를 채운다.
     *
     * <p>취소된 라인 제외는 조건이 아니라 <b>정의</b>라 항상 붙는다. 부분 취소된 주문은 상태가
     * 여전히 결제 상태이므로, 이 절이 빠지면 이미 되돌려준 라인이 판매 실적에 남는다.
     */
    private static String buildWhere(SalesCriteria criteria, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        clauses.add("i.canceled_at IS NULL");

        List<String> statuses = criteria.statuses();
        if (statuses == null || statuses.isEmpty()) {
            // 빈 목록의 뜻은 "전부"라, 그대로 두면 결제도 안 된 주문과 이미 환불한 주문이 판매
            // 실적에 섞인다. 여기서 기본값을 지어내지 않고 거부하는 이유는 무엇을 셀지가 조용히
            // 넓어지는 것이 이 저장소가 반복해서 겪은 사고의 형태이기 때문이다 — 서비스가 명시한다.
            throw new IllegalArgumentException("판매 통계는 주문 상태 조건 없이 집계할 수 없습니다");
        }
        clauses.add("o.status IN (" + String.join(", ", Collections.nCopies(statuses.size(), "?")) + ")");
        args.addAll(statuses);

        if (criteria.createdFrom() != null) {
            clauses.add("o.created_at >= ?");
            args.add(Timestamp.valueOf(criteria.createdFrom()));
        }
        if (criteria.createdToExclusive() != null) {
            clauses.add("o.created_at < ?");
            args.add(Timestamp.valueOf(criteria.createdToExclusive()));
        }

        return " WHERE " + String.join(" AND ", clauses);
    }
}
