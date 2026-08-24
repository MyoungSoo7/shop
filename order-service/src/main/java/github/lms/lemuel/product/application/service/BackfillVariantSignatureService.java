package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.application.port.out.VariantOptionMappingPort;
import github.lms.lemuel.product.domain.*;
import github.lms.lemuel.product.domain.LegacyOptionName.Segment;
import github.lms.lemuel.product.domain.OptionSignature.AxisSelection;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SKU ↔ 옵션 값 매핑 기록과 조합 서명 부여 (설계 Phase 2).
 *
 * <p>이 단계가 끝나야 옵션 조회가 문자열 선형 스캔에서 벗어난다. 매핑은 파셋 조회의 조인키가 되고,
 * 서명은 {@code (product_id, option_signature)} 유니크 인덱스 단건 조회의 키가 된다.
 *
 * <p><b>중복 서명을 DB 로 흘려보내지 않는다</b>: {@code "색상:빨강/사이즈:L"} 과
 * {@code "사이즈:L/색상:빨강"} 은 서로 다른 {@code option_name} 이지만 같은 조합이다. 문자열이
 * 유일성의 기준이던 시절엔 두 행이 공존할 수 있었고, 그게 바로 서명 유니크 인덱스가 드러내는 잠복
 * 중복이다. 유니크 위반으로 트랜잭션을 통째로 날리는 대신 상품 안에서 먼저 잡아 경고로 남긴다.
 */
@Service
public class BackfillVariantSignatureService implements BackfillVariantSignatureUseCase {

    private final LoadProductVariantPort loadVariantPort;
    private final SaveProductVariantPort saveVariantPort;
    private final LoadOptionCatalogPort loadCatalogPort;
    private final VariantOptionMappingPort mappingPort;

    public BackfillVariantSignatureService(LoadProductVariantPort loadVariantPort,
                                           SaveProductVariantPort saveVariantPort,
                                           LoadOptionCatalogPort loadCatalogPort,
                                           VariantOptionMappingPort mappingPort) {
        this.loadVariantPort = loadVariantPort;
        this.saveVariantPort = saveVariantPort;
        this.loadCatalogPort = loadCatalogPort;
        this.mappingPort = mappingPort;
    }

    @Override
    @Transactional
    public SignatureBackfillReport backfillAll() {
        SignatureBackfillReport report = SignatureBackfillReport.empty();
        for (Long productId : loadVariantPort.findProductIdsWithVariants()) {
            report = report.merge(backfillOne(productId));
        }
        return report;
    }

    @Override
    @Transactional
    public SignatureBackfillReport backfillProduct(Long productId) {
        return backfillOne(productId);
    }

    private SignatureBackfillReport backfillOne(Long productId) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> signatureOwner = new HashMap<>();
        int variants = 0;
        int mappings = 0;
        int signatures = 0;
        int skipped = 0;

        List<ProductVariant> all = loadVariantPort.loadByProductId(productId).stream()
                .sorted(Comparator.comparing(ProductVariant::getId))
                .toList();

        for (ProductVariant variant : all) {
            variants++;
            List<Resolved> resolved;
            try {
                resolved = resolve(productId, variant);
            } catch (ProductInvariantViolationException e) {
                skipped++;
                warnings.add("sku=" + variant.getSku() + " 건너뜀: " + e.getMessage());
                continue;
            }

            String signature = OptionSignature.of(resolved.stream()
                    .map(r -> new AxisSelection(r.axisId(), r.axisValueId()))
                    .toList());

            String owner = signatureOwner.putIfAbsent(signature, variant.getSku());
            if (owner != null) {
                skipped++;
                warnings.add("sku=" + variant.getSku() + " 건너뜀: 같은 옵션 조합이 이미 sku="
                        + owner + " 에 있습니다(표시명 순서만 다른 중복)");
                continue;
            }

            for (Resolved r : resolved) {
                mappingPort.save(ProductVariantOptionValue.of(
                        variant.getId(), r.productOptionAxisId(), r.productOptionValueId()));
                mappings++;
            }

            if (!signature.equals(variant.getOptionSignature())) {
                variant.assignOptionSignature(signature);
                saveVariantPort.save(variant);
                signatures++;
            }
        }

        return new SignatureBackfillReport(1, variants, mappings, signatures, skipped, warnings);
    }

    /**
     * 표시명 한 줄을 카탈로그 행들로 해석한다. 축·값·상품채택 중 하나라도 없으면 <b>만들지 않고</b>
     * 예외로 알린다 — 카탈로그를 만드는 책임은 Phase 1 백필에 있다.
     */
    private List<Resolved> resolve(Long productId, ProductVariant variant) {
        List<Segment> segments = LegacyOptionName.parse(variant.getOptionName());
        List<Resolved> resolved = new ArrayList<>(segments.size());

        for (Segment segment : segments) {
            String axisCode = OptionCode.fromDisplayName(segment.axisName(), "옵션 축 이름");
            OptionAxis axis = loadCatalogPort.findAxisByCode(axisCode)
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "표준 축이 없습니다(카탈로그 백필 선행 필요): " + axisCode));

            ProductOptionAxis productAxis = loadCatalogPort.findProductAxis(productId, axis.getId())
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "상품이 채택하지 않은 축입니다: " + axisCode));

            String valueCode = OptionCode.fromDisplayName(segment.valueName(), "옵션 값 이름");
            OptionAxisValue axisValue = loadCatalogPort.findAxisValueByCode(axis.getId(), valueCode)
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "표준 값이 없습니다: " + axisCode + "=" + valueCode));

            ProductOptionValue productValue = loadCatalogPort
                    .findProductValue(productAxis.getId(), axisValue.getId())
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "상품이 노출하지 않는 값입니다: " + axisCode + "=" + valueCode));

            resolved.add(new Resolved(axis.getId(), axisValue.getId(),
                    productAxis.getId(), productValue.getId()));
        }
        return resolved;
    }

    /** 한 차수의 해석 결과 — 서명용 전역 id 와 매핑용 상품 스코프 id. */
    private record Resolved(Long axisId, Long axisValueId,
                            Long productOptionAxisId, Long productOptionValueId) {
    }
}
