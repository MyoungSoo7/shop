package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.*;
import github.lms.lemuel.product.domain.OptionSignature.AxisSelection;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 옵션 선택 → SKU(variant) 변환.
 *
 * <p>상품이 채택한 축({@code product_option_axes})으로 선택을 검증하고, {@link OptionSignature} 를 계산해
 * {@code (product_id, option_signature)} 유니크 인덱스로 SKU 를 단건 조회한다.
 *
 * <p>이전에는 {@code "색상:빨강/사이즈:L"} 문자열을 조립해 상품의 전체 SKU 를 선형 스캔했다. 구분자·순서·공백
 * 중 하나만 어긋나도 조회가 조용히 실패했고 SKU 수에 비례해 느려졌다. 이관 기간 동안 그 옛 경로를
 * 폴백으로 남겨 두었으나, 백필 완료(모든 SKU 가 서명 보유)와 생성 경로의 카탈로그 등록으로
 * <b>서명 없는 SKU 가 만들어질 수 없게 된 뒤</b> 걷어냈다.
 *
 * <p>선택 <b>순서는 의미가 없다</b> — 서명이 축 id 로 정렬되므로 색상을 먼저 고르든 사이즈를 먼저 고르든
 * 같은 SKU 를 찾는다. 검증은 집합 기준(필수 축 빠짐없이, 축당 한 번, 노출 중인 값)이다.
 *
 * <p>{@code products.options_json} 은 더 이상 읽지 않는다. 상품 등록 시점의 옵션 트리를 남긴 감사 사본이며,
 * 진열·선택 검증의 정본은 카탈로그 테이블이다.
 */
@Service
@Transactional(readOnly = true)
public class ResolveOptionSelectionService implements ResolveOptionSelectionUseCase {

    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final LoadOptionCatalogPort loadCatalogPort;

    public ResolveOptionSelectionService(LoadProductPort loadProductPort,
                                         LoadProductVariantPort loadVariantPort,
                                         LoadOptionCatalogPort loadCatalogPort) {
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.loadCatalogPort = loadCatalogPort;
    }

    @Override
    public ProductVariant resolve(Long productId, List<Selection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new ProductInvariantViolationException("옵션 선택이 비어 있습니다");
        }

        List<ProductOptionAxis> productAxes = loadCatalogPort.loadProductAxes(productId);
        if (productAxes.isEmpty()) {
            // 상품이 없는 것과 옵션이 없는 것은 다른 실패다 — 404 와 400 을 뭉뚱그리지 않는다.
            loadProductPort.findById(productId)
                    .orElseThrow(() -> new ProductNotFoundException(productId));
            throw new ProductInvariantViolationException(
                    "옵션이 정의되지 않은 상품입니다: productId=" + productId);
        }

        String signature = OptionSignature.of(validateAgainstCatalog(productAxes, selections));
        return loadVariantPort.loadByOptionSignature(productId, signature)
                .orElseThrow(() -> new ProductInvariantViolationException(
                        "선택한 옵션 조합에 대응하는 SKU 가 없습니다: " + describe(selections)));
    }

    /**
     * 선택을 상품 카탈로그로 검증하고 서명 입력을 만든다.
     *
     * <p>검증은 <b>집합</b> 기준이다: 상품이 필수로 요구하는 축을 빠짐없이, 각 축을 정확히 한 번,
     * 상품이 노출 중인 값으로만 골라야 한다. 순서는 보지 않는다.
     */
    private List<AxisSelection> validateAgainstCatalog(List<ProductOptionAxis> productAxes,
                                                       List<Selection> selections) {
        List<AxisSelection> resolved = new ArrayList<>(selections.size());
        Set<Long> chosenAxisIds = new LinkedHashSet<>();

        for (Selection selection : selections) {
            OptionAxis axis = loadCatalogPort
                    .findAxisByCode(OptionCode.fromDisplayName(selection.name(), "옵션명"))
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "존재하지 않는 옵션 축: " + selection.name()));

            ProductOptionAxis productAxis = productAxes.stream()
                    .filter(a -> a.getAxisId().equals(axis.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "이 상품이 취급하지 않는 옵션 축: " + selection.name()));

            if (!chosenAxisIds.add(axis.getId())) {
                throw new ProductInvariantViolationException(
                        "같은 옵션 축을 두 번 선택했습니다: " + selection.name());
            }

            OptionAxisValue axisValue = loadCatalogPort
                    .findAxisValueByCode(axis.getId(),
                            OptionCode.fromDisplayName(selection.value(), "선택값"))
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "존재하지 않는 옵션 값: " + selection.name() + "=" + selection.value()));

            ProductOptionValue productValue = loadCatalogPort
                    .findProductValue(productAxis.getId(), axisValue.getId())
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "이 상품이 판매하지 않는 옵션 값: " + selection.name() + "=" + selection.value()));

            if (!productValue.isActive()) {
                throw new ProductInvariantViolationException(
                        "현재 선택할 수 없는 옵션 값입니다: " + selection.name() + "=" + selection.value());
            }

            resolved.add(new AxisSelection(axis.getId(), axisValue.getId()));
        }

        String missing = productAxes.stream()
                .filter(ProductOptionAxis::isRequired)
                .filter(a -> !chosenAxisIds.contains(a.getAxisId()))
                .map(a -> loadCatalogPort.findAxisById(a.getAxisId())
                        .map(OptionAxis::getName)
                        .orElse("axisId=" + a.getAxisId()))
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new ProductInvariantViolationException("옵션 선택이 불완전합니다 — 필수 축 미선택: " + missing);
        }

        return resolved;
    }

    /** 실패 메시지용 사람이 읽는 선택 요약. */
    private static String describe(List<Selection> selections) {
        return selections.stream()
                .map(s -> s.name() + ":" + s.value())
                .collect(Collectors.joining("/"));
    }
}
