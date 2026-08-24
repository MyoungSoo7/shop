package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveOptionCatalogPort;
import github.lms.lemuel.product.domain.*;
import github.lms.lemuel.product.domain.LegacyOptionName.Segment;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 레거시 표시명({@code "색상:빨강/사이즈:L"}) → 옵션 축/값 카탈로그 백필.
 *
 * <p>정본이 문자열이던 시절의 SKU 를 테이블 구조로 끌어올린다. 설계상 이 서비스는 <b>기존 데이터를
 * 지우거나 고치지 않는다</b> — {@code option_name} 도 {@code options_json} 도 그대로 두고 카탈로그만
 * 채운다. 그래서 실패해도 판매 경로가 깨지지 않고, 재실행이 안전하다.
 *
 * <p><b>멱등</b>: 축·값·상품축·상품값 모두 "있으면 재사용, 없으면 생성"이라 2 회차 실행의 생성 건수는 0 이다.
 *
 * <p><b>관대하지 않다</b>: 파싱할 수 없는 표시명, 코드로 쓸 수 없는 이름, 상품 안에서 차수가 어긋나는
 * SKU 는 만들지 않고 경고로 남긴다. 추측해서 채우면 잘못된 카탈로그가 조용히 정본이 된다.
 *
 * <p>전체 백필은 하나의 트랜잭션이다 — 부분 성공 상태로 남기보다 실패 시 통째로 되돌리고
 * 원인을 고쳐 재실행하는 편이 낫다(멱등이라 재실행 비용이 낮다).
 */
@Service
public class BackfillOptionCatalogService implements BackfillOptionCatalogUseCase {

    private static final OptionInputType DEFAULT_INPUT_TYPE = OptionInputType.SELECT;

    private final LoadProductVariantPort loadVariantPort;
    private final LoadOptionCatalogPort loadCatalogPort;
    private final SaveOptionCatalogPort saveCatalogPort;

    public BackfillOptionCatalogService(LoadProductVariantPort loadVariantPort,
                                        LoadOptionCatalogPort loadCatalogPort,
                                        SaveOptionCatalogPort saveCatalogPort) {
        this.loadVariantPort = loadVariantPort;
        this.loadCatalogPort = loadCatalogPort;
        this.saveCatalogPort = saveCatalogPort;
    }

    @Override
    @Transactional
    public BackfillReport backfillAll() {
        BackfillReport report = BackfillReport.empty();
        for (Long productId : loadVariantPort.findProductIdsWithVariants()) {
            report = report.merge(backfillOne(productId));
        }
        return report;
    }

    @Override
    @Transactional
    public BackfillReport backfillProduct(Long productId) {
        return backfillOne(productId);
    }

    private BackfillReport backfillOne(Long productId) {
        Counters counters = new Counters();
        List<String> warnings = new ArrayList<>();

        List<ProductVariant> variants = loadVariantPort.loadByProductId(productId).stream()
                .sorted(Comparator.comparing(ProductVariant::getId))
                .toList();

        for (ProductVariant variant : variants) {
            counters.variants++;
            try {
                applyVariant(productId, variant, counters, warnings);
            } catch (ProductInvariantViolationException e) {
                warnings.add("sku=" + variant.getSku() + " 건너뜀: " + e.getMessage());
            }
        }

        return new BackfillReport(1, counters.variants, counters.axes, counters.axisValues,
                counters.productAxes, counters.productValues, warnings);
    }

    private void applyVariant(Long productId, ProductVariant variant,
                              Counters counters, List<String> warnings) {
        List<Segment> segments = LegacyOptionName.parse(variant.getOptionName());

        for (int depth = 0; depth < segments.size(); depth++) {
            Segment segment = segments.get(depth);

            OptionAxis axis = resolveAxis(segment.axisName(), counters);
            ProductOptionAxis productAxis = resolveProductAxis(productId, axis, depth, counters);
            if (productAxis.getSortOrder() != depth) {
                warnings.add("sku=" + variant.getSku() + " 축 '" + segment.axisName()
                        + "' 차수 불일치: 저장=" + productAxis.getSortOrder() + ", 관측=" + depth);
            }

            OptionAxisValue axisValue = resolveAxisValue(axis, segment.valueName(), counters);
            resolveProductValue(productAxis, axisValue, counters);
        }
    }

    private OptionAxis resolveAxis(String axisName, Counters counters) {
        String code = toCode(axisName, "옵션 축 이름");
        return loadCatalogPort.findAxisByCode(code)
                .orElseGet(() -> {
                    counters.axes++;
                    return saveCatalogPort.saveAxis(
                            OptionAxis.create(code, axisName, DEFAULT_INPUT_TYPE));
                });
    }

    private ProductOptionAxis resolveProductAxis(Long productId, OptionAxis axis,
                                                 int depth, Counters counters) {
        return loadCatalogPort.findProductAxis(productId, axis.getId())
                .orElseGet(() -> {
                    counters.productAxes++;
                    return saveCatalogPort.saveProductAxis(
                            ProductOptionAxis.create(productId, axis.getId(), depth, true));
                });
    }

    private OptionAxisValue resolveAxisValue(OptionAxis axis, String valueName, Counters counters) {
        String code = toCode(valueName, "옵션 값 이름");
        return loadCatalogPort.findAxisValueByCode(axis.getId(), code)
                .orElseGet(() -> {
                    int nextOrder = loadCatalogPort.loadAxisValues(axis.getId()).size();
                    counters.axisValues++;
                    return saveCatalogPort.saveAxisValue(
                            OptionAxisValue.create(axis.getId(), code, valueName, null, nextOrder));
                });
    }

    private void resolveProductValue(ProductOptionAxis productAxis, OptionAxisValue axisValue,
                                     Counters counters) {
        // orElseGet 의 반환값을 버리고 부작용(저장·카운트)만 쓰던 자리다(S2201).
        // "없으면 만든다"를 if 로 드러낸다 — 동작은 그대로이고, 반환값을 쓰는지 여부가 애매하지 않다.
        if (loadCatalogPort.findProductValue(productAxis.getId(), axisValue.getId()).isEmpty()) {
            int nextOrder = loadCatalogPort.loadProductValues(productAxis.getId()).size();
            counters.productValues++;
            saveCatalogPort.saveProductValue(
                    ProductOptionValue.create(productAxis.getId(), axisValue.getId(), nextOrder));
        }
    }

    /** 표시 이름 → 기계 코드. 규칙은 {@link OptionCode} 가 소유한다(백필과 조회가 같은 규칙을 써야 함). */
    private static String toCode(String name, String what) {
        return OptionCode.fromDisplayName(name, what);
    }

    /** 생성 건수 집계용 가변 카운터 — 서비스 내부 전용. */
    private static final class Counters {
        private int variants;
        private int axes;
        private int axisValues;
        private int productAxes;
        private int productValues;
    }
}
