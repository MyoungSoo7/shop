package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;

import java.util.List;

/**
 * 옵션 파셋 검색 — "색상=빨강 또는 파랑, 사이즈=L 인 상품" 과 그때 고를 수 있는 값들.
 *
 * <p>옵션이 문자열이던 시절에는 성립하지 않던 질의다. 축·값이 테이블이 되고 SKU 와 매핑되면서
 * 비로소 조인 한 번으로 답할 수 있게 됐다.
 */
public interface SearchProductFacetsUseCase {

    FacetSearchResult search(List<String> optionTokens, Long categoryId, boolean availableOnly);

    /** 결과 상품과, 그 화면에서 이어 고를 수 있는 파셋. */
    record FacetSearchResult(List<Product> products, List<Facet> facets) {
    }

    record Facet(String axisCode, String axisName, List<FacetValue> values) {
    }

    /**
     * @param selected 지금 선택돼 있는 값인가 — 화면이 체크 상태를 서버 판단으로 그린다
     * @param productCount 이 값을 (추가로) 고르면 남는 상품 수
     */
    record FacetValue(String code, String name, long productCount, boolean selected) {
    }
}
