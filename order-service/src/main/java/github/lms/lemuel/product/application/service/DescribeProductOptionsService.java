package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DescribeProductOptionsUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.VariantOptionMappingPort;
import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.ProductOptionAxis;
import github.lms.lemuel.product.domain.ProductOptionValue;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantOptionValue;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 구매자용 옵션 트리 조회.
 *
 * <p>정본은 {@code product_option_axes}/{@code product_option_values} 다 — {@code products.options_json}
 * 은 등록 시점의 감사 사본이라 읽지 않는다. 이 규칙은
 * {@link ResolveOptionSelectionService} 와 같아야 한다. 그리는 쪽과 검증하는 쪽이 다른 원천을 보면
 * 화면에 있는데 못 고르는 값(또는 그 반대)이 생긴다.
 *
 * <p>조회 비용: 축·값은 상품당 한 번씩만 읽고, SKU 매핑은 SKU 수만큼 읽는다. 매핑을 코드로 바꾸는 데
 * 필요한 사전은 위에서 읽은 축·값으로 <b>메모리에 만들어</b> 쓰므로, SKU 하나당 축 조회가 다시 나가는
 * 일은 없다({@link DescribeVariantOptionsService} 는 단건 조회라 그 방식이 맞지만, 여기는 목록이다).
 */
@Service
@Transactional(readOnly = true)
public class DescribeProductOptionsService implements DescribeProductOptionsUseCase {

    private final LoadProductPort loadProductPort;
    private final LoadOptionCatalogPort loadCatalogPort;
    private final LoadProductVariantPort loadVariantPort;
    private final VariantOptionMappingPort mappingPort;

    public DescribeProductOptionsService(LoadProductPort loadProductPort,
                                         LoadOptionCatalogPort loadCatalogPort,
                                         LoadProductVariantPort loadVariantPort,
                                         VariantOptionMappingPort mappingPort) {
        this.loadProductPort = loadProductPort;
        this.loadCatalogPort = loadCatalogPort;
        this.loadVariantPort = loadVariantPort;
        this.mappingPort = mappingPort;
    }

    @Override
    public ProductOptions describe(Long productId) {
        // 상품이 없는 것과 옵션이 없는 것은 다른 답이다 — 존재 확인을 먼저 한다.
        loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        List<ProductOptionAxis> productAxes = loadCatalogPort.loadProductAxes(productId);
        if (productAxes.isEmpty()) {
            return new ProductOptions(productId, List.of(), List.of());
        }

        Map<Long, String> axisCodeByProductAxisId = new HashMap<>();
        Map<Long, String> valueCodeByProductValueId = new HashMap<>();
        List<Axis> axes = new ArrayList<>(productAxes.size());

        for (ProductOptionAxis productAxis : productAxes) {
            Optional<OptionAxis> axis = loadCatalogPort.findAxisById(productAxis.getAxisId());
            if (axis.isEmpty() || !axis.get().isActive()) {
                continue; // 표준 축이 내려간 상태 — 고를 수 없는 축을 그리지 않는다
            }
            axisCodeByProductAxisId.put(productAxis.getId(), axis.get().getCode());

            List<Value> values = new ArrayList<>();
            for (ProductOptionValue productValue : loadCatalogPort.loadProductValues(productAxis.getId())) {
                Optional<OptionAxisValue> axisValue =
                        loadCatalogPort.findAxisValueById(productValue.getAxisValueId());
                if (axisValue.isEmpty()) {
                    continue;
                }
                valueCodeByProductValueId.put(productValue.getId(), axisValue.get().getCode());
                // 상품이 내린 값(active=false)과 표준값이 내려간 값은 둘 다 고를 수 없다.
                // resolve 가 거절하는 것과 같은 조건이라 화면에 아예 내지 않는다.
                if (!productValue.isActive() || !axisValue.get().isActive()) {
                    continue;
                }
                values.add(new Value(axisValue.get().getCode(), axisValue.get().getName(),
                        axisValue.get().getSwatchHex(), productValue.getSortOrder()));
            }
            values.sort(Comparator.comparingInt(Value::sortOrder));

            // 자유입력 상한은 TEXT 축에서만 뜻이 있다. 선택형 축에 실어 보내면 화면이
            // "여기도 적을 수 있나" 하고 헷갈릴 자리를 만든다.
            Integer textMaxLength = axis.get().getInputType().hasEnumeratedValues()
                    ? null
                    : productAxis.effectiveTextMaxLength();
            axes.add(new Axis(productAxis.getSortOrder(), axis.get().getCode(), axis.get().getName(),
                    axis.get().getInputType(), productAxis.isRequired(), List.copyOf(values),
                    textMaxLength));
        }
        axes.sort(Comparator.comparingInt(Axis::sortOrder));

        return new ProductOptions(productId, List.copyOf(axes),
                combinations(productId, axisCodeByProductAxisId, valueCodeByProductValueId));
    }

    private List<Combination> combinations(Long productId,
                                           Map<Long, String> axisCodeByProductAxisId,
                                           Map<Long, String> valueCodeByProductValueId) {
        List<Combination> combinations = new ArrayList<>();
        for (ProductVariant variant : loadVariantPort.loadByProductId(productId)) {
            List<ProductVariantOptionValue> mappings = mappingPort.loadByVariantId(variant.getId());
            if (mappings.isEmpty()) {
                continue; // 백필 전 SKU — 조합을 코드로 확정할 수 없다
            }
            // 순서를 보존해 담는다. 서명은 축 id 정렬이라 순서에 의존하지 않지만,
            // 화면이 축 순서대로 읽을 수 있으면 디버깅할 때 눈으로 대조가 된다.
            Map<String, String> selections = new LinkedHashMap<>();
            boolean complete = true;
            for (ProductVariantOptionValue mapping : mappings) {
                String axisCode = axisCodeByProductAxisId.get(mapping.getProductOptionAxisId());
                String valueCode = valueCodeByProductValueId.get(mapping.getProductOptionValueId());
                if (axisCode == null || valueCode == null) {
                    complete = false;
                    break;
                }
                selections.put(axisCode, valueCode);
            }
            if (!complete) {
                continue;
            }
            combinations.add(new Combination(
                    selections.entrySet().stream()
                            .map(e -> new Selection(e.getKey(), e.getValue()))
                            .toList(),
                    variant.isAvailable(),
                    variant.getAdditionalPrice()));
        }
        return List.copyOf(combinations);
    }
}
