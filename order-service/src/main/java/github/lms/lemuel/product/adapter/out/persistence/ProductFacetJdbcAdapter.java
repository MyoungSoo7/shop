package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadProductFacetPort;
import github.lms.lemuel.product.domain.OptionFacetQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 옵션 파셋 조회 — 매핑 테이블 조인.
 *
 * <p>JPQL 이 아니라 JDBC 인 이유: 축 간 AND 를 SKU 단위
 * {@code HAVING count(DISTINCT 축) = 선택축수} 로 표현해야 하는데, 선택 축 수와
 * {@code (축, 값)} 쌍 목록이 요청마다 달라 정적 JPQL 로는 담기 어렵다.
 *
 * <p>조인 사슬은 언제나 같아서 {@link #joins(String)} 하나로 만든다 — 별칭만 갈아 끼운다.
 * <pre>
 *   product_variants
 *     → product_variant_option_values          (SKU 가 고른 값)
 *     → product_option_values → option_axis_values   (값의 코드·이름)
 *     → product_option_axes   → option_axes          (축의 코드·이름)
 * </pre>
 */
@Component
public class ProductFacetJdbcAdapter implements LoadProductFacetPort {

    private final NamedParameterJdbcTemplate jdbc;

    public ProductFacetJdbcAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 별칭 접미사만 다른 동일한 조인 사슬. {@code s} 가 빈 문자열이면 v/m/pov/av/poa/ax. */
    private static String joins(String s) {
        return """
                FROM opslab.product_variants v%1$s
                         JOIN opslab.product_variant_option_values m%1$s ON m%1$s.variant_id = v%1$s.id
                         JOIN opslab.product_option_values pov%1$s ON pov%1$s.id = m%1$s.product_option_value_id
                         JOIN opslab.option_axis_values av%1$s ON av%1$s.id = pov%1$s.axis_value_id
                         JOIN opslab.product_option_axes poa%1$s ON poa%1$s.id = m%1$s.product_option_axis_id
                         JOIN opslab.option_axes ax%1$s ON ax%1$s.id = poa%1$s.axis_id
                """.formatted(s);
    }

    @Override
    // 동적 SQL 경고(java:S2077) 억제 — 요청에서 온 값(축 코드·값 코드·카테고리)은 전부 명명
    // 파라미터로 바인딩한다. 문자열로 조립하는 건 별칭 접미사와 고정 조인 사슬뿐이다.
    @SuppressWarnings("java:S2077")
    public List<Long> findProductIds(OptionFacetQuery query, Long categoryId, boolean availableOnly) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (query.isEmpty()) {
            String sql = "SELECT DISTINCT v.product_id\n" + joins("")
                    + where("", "v", query, categoryId, availableOnly, params) + "\nORDER BY 1";
            return jdbc.queryForList(sql, params, Long.class);
        }
        return jdbc.queryForList(matchingProductIdsSql("", params, query, categoryId, availableOnly)
                + "\nORDER BY product_id", params, Long.class);
    }

    @Override
    // 동적 SQL 경고(java:S2077) 억제 — 요청에서 온 값(축 코드·값 코드·카테고리)은 전부 명명
    // 파라미터로 바인딩한다. 문자열로 조립하는 건 별칭 접미사와 고정 조인 사슬뿐이다.
    @SuppressWarnings("java:S2077")
    public List<FacetCount> countFacets(OptionFacetQuery query, Long categoryId,
                                        boolean availableOnly, String restrictToAxisCode) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ax.code AS axis_code, ax.name AS axis_name, poa.sort_order AS axis_sort,\n")
                .append("       av.code AS value_code, av.name AS value_name, pov.sort_order AS value_sort,\n")
                .append("       count(DISTINCT v.product_id) AS product_count\n")
                .append(joins(""))
                // 개수의 모집단은 "필터를 만족하는 상품" 이고, 세는 대상은 그 상품들이 가진 값 전부다.
                // 그래서 바깥 질의에는 (축,값) 조건을 걸지 않고 상품 집합만 좁힌다.
                .append(where("", "v", OptionFacetQuery.empty(), categoryId, availableOnly, params));
        if (!query.isEmpty()) {
            sql.append(" AND v.product_id IN (")
               .append(matchingProductIdsSql("2", params, query, categoryId, availableOnly))
               .append(")");
        }
        if (restrictToAxisCode != null) {
            sql.append(" AND ax.code = :restrictAxis");
            params.addValue("restrictAxis", restrictToAxisCode);
        }
        sql.append("\nGROUP BY ax.code, ax.name, poa.sort_order, av.code, av.name, pov.sort_order")
           .append("\nORDER BY axis_sort, axis_code, value_sort, value_code");

        return jdbc.query(sql.toString(), params, (rs, i) -> new FacetCount(
                rs.getString("axis_code"), rs.getString("axis_name"), rs.getInt("axis_sort"),
                rs.getString("value_code"), rs.getString("value_name"), rs.getInt("value_sort"),
                rs.getLong("product_count")));
    }

    /**
     * 필터를 만족하는 상품 id 를 내는 서브쿼리.
     *
     * <p>{@code GROUP BY} 를 SKU({@code v.id}) 단위로 잡는 것이 핵심이다 — 상품 단위로 잡으면
     * 빨강 SKU 와 L SKU 를 따로 가진 상품이 "빨강 L" 에 걸린다(살 수 없는 조합인데 결과에 나온다).
     */
    private String matchingProductIdsSql(String alias, MapSqlParameterSource params,
                                         OptionFacetQuery query, Long categoryId, boolean availableOnly) {
        String v = "v" + alias;
        String axisCountParam = "axisCount" + alias;
        params.addValue(axisCountParam, query.axisCount());
        return "SELECT " + v + ".product_id\n" + joins(alias)
                + where(alias, v, query, categoryId, availableOnly, params)
                + "\nGROUP BY " + v + ".id, " + v + ".product_id"
                + "\nHAVING count(DISTINCT ax" + alias + ".code) = :" + axisCountParam;
    }

    private String where(String alias, String variantAlias, OptionFacetQuery query, Long categoryId,
                         boolean availableOnly, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (availableOnly) {
            // 파셋은 살 수 있는 것만 센다 — 품절·단종을 세면 눌러도 빈 결과가 나온다.
            where.append(" AND ").append(variantAlias).append(".status = 'ACTIVE'")
                 .append(" AND ").append(variantAlias).append(".stock_quantity > 0");
        }
        if (categoryId != null) {
            String param = "categoryId" + alias;
            where.append(" AND EXISTS (SELECT 1 FROM opslab.product_ecommerce_categories pc").append(alias)
                 .append(" WHERE pc").append(alias).append(".product_id = ").append(variantAlias)
                 .append(".product_id AND pc").append(alias).append(".category_id = :").append(param).append(")");
            params.addValue(param, categoryId);
        }
        where.append(pairPredicate(alias, query, params));
        return where.toString();
    }

    /** 같은 축 안 OR, 축 간 AND — OR 목록은 여기서, AND 는 HAVING 의 축 개수 비교가 담당한다. */
    private String pairPredicate(String alias, OptionFacetQuery query, MapSqlParameterSource params) {
        List<String> ors = new ArrayList<>();
        int i = 0;
        for (OptionFacetQuery.AxisValue pair : query.pairs()) {
            String a = "a" + alias + i;
            String val = "val" + alias + i;
            ors.add("(ax" + alias + ".code = :" + a + " AND av" + alias + ".code = :" + val + ")");
            params.addValue(a, pair.axisCode());
            params.addValue(val, pair.valueCode());
            i++;
        }
        return ors.isEmpty() ? "" : " AND (" + String.join(" OR ", ors) + ")";
    }
}
