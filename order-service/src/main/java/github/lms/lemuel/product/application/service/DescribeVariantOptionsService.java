package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DescribeVariantOptionsUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.VariantOptionMappingPort;
import github.lms.lemuel.product.domain.*;
import github.lms.lemuel.product.domain.LegacyOptionName.Segment;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * SKU → 옵션 설명(축·값의 코드와 이름). 주문 옵션 스냅샷의 원천.
 *
 * <p>카탈로그 매핑({@code product_variant_option_values})이 있으면 그것을 쓰고, 아직 백필되지 않은
 * SKU 는 표시명 {@code "색상:빨강/사이즈:L"} 을 파싱해 같은 모양으로 만들어 준다. 두 경로가 같은
 * {@link OptionCode} 규칙을 쓰므로 백필 전후로 스냅샷의 코드 값이 달라지지 않는다.
 *
 * <p><b>예외를 던지지 않는다.</b> 옵션을 설명하지 못한다고 주문 생성이 실패하면 안 된다 —
 * 재고 차감과 금액은 이미 SKU 로 확정돼 있고, 스냅샷은 그 위에 얹는 표시 정보다.
 */
@Service
@Transactional(readOnly = true)
public class DescribeVariantOptionsService implements DescribeVariantOptionsUseCase {

    private final LoadProductVariantPort loadVariantPort;
    private final LoadOptionCatalogPort loadCatalogPort;
    private final VariantOptionMappingPort mappingPort;

    public DescribeVariantOptionsService(LoadProductVariantPort loadVariantPort,
                                         LoadOptionCatalogPort loadCatalogPort,
                                         VariantOptionMappingPort mappingPort) {
        this.loadVariantPort = loadVariantPort;
        this.loadCatalogPort = loadCatalogPort;
        this.mappingPort = mappingPort;
    }

    @Override
    public List<OptionDescriptor> describe(Long variantId) {
        if (variantId == null) {
            return List.of();
        }
        List<OptionDescriptor> fromCatalog = describeFromCatalog(variantId);
        if (!fromCatalog.isEmpty()) {
            return fromCatalog;
        }
        return loadVariantPort.loadById(variantId)
                .map(ProductVariant::getOptionName)
                .map(DescribeVariantOptionsService::describeFromLegacyLabel)
                .orElseGet(List::of);
    }

    private List<OptionDescriptor> describeFromCatalog(Long variantId) {
        List<ProductVariantOptionValue> mappings = mappingPort.loadByVariantId(variantId);
        if (mappings.isEmpty()) {
            return List.of();
        }

        List<OptionDescriptor> descriptors = new ArrayList<>(mappings.size());
        for (ProductVariantOptionValue mapping : mappings) {
            Optional<ProductOptionAxis> productAxis =
                    loadCatalogPort.findProductAxisById(mapping.getProductOptionAxisId());
            Optional<ProductOptionValue> productValue =
                    loadCatalogPort.findProductValueById(mapping.getProductOptionValueId());
            if (productAxis.isEmpty() || productValue.isEmpty()) {
                return List.of(); // 매핑이 깨졌다 — 반쪽 스냅샷보다 레거시 라벨 파싱이 낫다
            }

            Optional<OptionAxis> axis = loadCatalogPort.findAxisById(productAxis.get().getAxisId());
            Optional<OptionAxisValue> value =
                    loadCatalogPort.findAxisValueById(productValue.get().getAxisValueId());
            if (axis.isEmpty() || value.isEmpty()) {
                return List.of();
            }

            descriptors.add(new OptionDescriptor(
                    productAxis.get().getSortOrder(),
                    axis.get().getCode(), axis.get().getName(),
                    value.get().getCode(), value.get().getName()));
        }

        return descriptors.stream()
                .sorted(Comparator.comparingInt(OptionDescriptor::sortOrder))
                .toList();
    }

    /** 백필 전 SKU 용 — 표시명을 그대로 풀어 쓴다. 파싱 불가면 빈 목록. */
    private static List<OptionDescriptor> describeFromLegacyLabel(String optionName) {
        try {
            List<Segment> segments = LegacyOptionName.parse(optionName);
            List<OptionDescriptor> descriptors = new ArrayList<>(segments.size());
            for (int depth = 0; depth < segments.size(); depth++) {
                Segment segment = segments.get(depth);
                descriptors.add(new OptionDescriptor(depth,
                        OptionCode.fromDisplayName(segment.axisName(), "옵션 축 이름"),
                        segment.axisName(),
                        OptionCode.fromDisplayName(segment.valueName(), "옵션 값 이름"),
                        segment.valueName()));
            }
            return List.copyOf(descriptors);
        } catch (ProductInvariantViolationException e) {
            return List.of();
        }
    }
}
