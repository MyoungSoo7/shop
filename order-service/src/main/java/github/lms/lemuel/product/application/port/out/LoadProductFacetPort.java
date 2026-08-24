package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.OptionFacetQuery;

import java.util.List;

/**
 * 옵션 파셋 조회 포트.
 *
 * <p>{@code product_variant_option_values} 매핑을 조인해 "이 값 조합을 실제로 살 수 있는 상품" 을 찾는다.
 * 문자열 {@code option_name} 시절에는 LIKE 로도 흉내 낼 수 없던 질의다 — 축 단위 조건을 걸 수 없었기 때문이다.
 */
public interface LoadProductFacetPort {

    /**
     * 필터를 만족하는 상품 id (오름차순).
     *
     * <p>축 간 AND 는 <b>SKU 하나 안에서</b> 성립해야 한다. 상품 단위로 AND 를 걸면 빨강 SKU 와 L SKU 를
     * 따로 가진 상품이 "빨강 L" 검색에 걸리는데, 정작 그 조합은 살 수 없다.
     */
    List<Long> findProductIds(OptionFacetQuery query, Long categoryId, boolean availableOnly);

    /**
     * 파셋별 상품 수.
     *
     * @param restrictToAxisCode null 이면 전체 축, 값이 있으면 그 축의 값들만 센다
     */
    List<FacetCount> countFacets(OptionFacetQuery query, Long categoryId,
                                 boolean availableOnly, String restrictToAxisCode);

    record FacetCount(String axisCode, String axisName, int axisSortOrder,
                      String valueCode, String valueName, int valueSortOrder,
                      long productCount) {
    }
}
