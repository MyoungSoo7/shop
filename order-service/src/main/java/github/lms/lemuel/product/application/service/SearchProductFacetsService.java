package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.SearchProductFacetsUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductFacetPort;
import github.lms.lemuel.product.application.port.out.LoadProductFacetPort.FacetCount;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.OptionFacetQuery;
import github.lms.lemuel.product.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 옵션 파셋 검색.
 *
 * <p><b>파셋 개수는 축마다 다른 조건으로 센다.</b> 색상=빨강을 고른 상태에서 색상 파셋을 모든 선택을
 * 적용해 세면 파랑이 0 이 되어 화면에서 사라지고, 사용자는 빨강에 파랑을 <b>추가</b>할 수 없게 된다.
 * 그래서 각 축의 개수는 "자기 축 선택을 뺀" 조건으로 센다({@link OptionFacetQuery#without(String)}).
 * 선택하지 않은 축은 모든 선택을 적용해 센다 — 그쪽은 좁히는 게 맞다.
 *
 * <p>질의 수는 1 + (선택한 축 수) 다. 상품 하나가 가진 축은 많아야 몇 개라 그대로 둔다 —
 * 한 번에 처리하려고 SQL 을 꼬면 위의 규칙이 어디에 있는지 알 수 없게 된다.
 */
@Service
@Transactional(readOnly = true)
public class SearchProductFacetsService implements SearchProductFacetsUseCase {

    private final LoadProductFacetPort facetPort;
    private final LoadProductPort loadProductPort;

    public SearchProductFacetsService(LoadProductFacetPort facetPort, LoadProductPort loadProductPort) {
        this.facetPort = facetPort;
        this.loadProductPort = loadProductPort;
    }

    @Override
    public FacetSearchResult search(List<String> optionTokens, Long categoryId, boolean availableOnly) {
        OptionFacetQuery query = OptionFacetQuery.of(optionTokens);

        List<Product> products = facetPort.findProductIds(query, categoryId, availableOnly).stream()
                .map(loadProductPort::findById)
                .flatMap(java.util.Optional::stream)
                .toList();

        return new FacetSearchResult(products, buildFacets(query, categoryId, availableOnly));
    }

    private List<Facet> buildFacets(OptionFacetQuery query, Long categoryId, boolean availableOnly) {
        Set<String> selectedAxes = query.axisCodes();

        List<FacetCount> counts = new ArrayList<>(
                // 선택하지 않은 축: 모든 선택을 적용해 센다(좁히는 방향).
                facetPort.countFacets(query, categoryId, availableOnly, null).stream()
                        .filter(c -> !selectedAxes.contains(c.axisCode()))
                        .toList());

        // 선택한 축: 자기 선택을 빼고 센다(형제 값을 추가로 고를 수 있게).
        for (String axisCode : selectedAxes) {
            counts.addAll(facetPort.countFacets(
                    query.without(axisCode), categoryId, availableOnly, axisCode));
        }

        Map<String, Facet> byAxis = new LinkedHashMap<>();
        Map<String, Integer> axisOrder = new LinkedHashMap<>();
        Map<String, List<FacetValue>> valuesByAxis = new LinkedHashMap<>();

        counts.stream()
                .sorted(Comparator.comparingInt(FacetCount::axisSortOrder)
                        .thenComparing(FacetCount::axisCode)
                        .thenComparingInt(FacetCount::valueSortOrder)
                        .thenComparing(FacetCount::valueCode))
                .forEach(c -> {
                    axisOrder.putIfAbsent(c.axisCode(), c.axisSortOrder());
                    byAxis.putIfAbsent(c.axisCode(), new Facet(c.axisCode(), c.axisName(), List.of()));
                    valuesByAxis.computeIfAbsent(c.axisCode(), k -> new ArrayList<>())
                            .add(new FacetValue(c.valueCode(), c.valueName(), c.productCount(),
                                    query.valueCodesOf(c.axisCode()).contains(c.valueCode())));
                });

        return byAxis.values().stream()
                .map(f -> new Facet(f.axisCode(), f.axisName(),
                        List.copyOf(valuesByAxis.getOrDefault(f.axisCode(), List.of()))))
                .sorted(Comparator.comparingInt(f -> axisOrder.getOrDefault(f.axisCode(), 0)))
                .toList();
    }
}
