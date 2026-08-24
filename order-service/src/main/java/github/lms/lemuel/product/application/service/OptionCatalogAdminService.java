package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ManageOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.SaveOptionCatalogPort;
import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.OptionInputType;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 표준 옵션 축·값 카탈로그 관리.
 *
 * <p>축과 값은 상품 간 <b>재사용</b>되는 카탈로그다. 그래서 여기서 지키는 규칙은 "이 상품이 팔 수
 * 있는가"가 아니라 "카탈로그가 갈라지지 않는가"에 있다 — 같은 코드의 축이 두 벌이면 파셋 집계가
 * 둘로 쪼개지고, 표시색 없는 SWATCH 값이 섞이면 화면이 빈 칩을 그린다.
 *
 * <p><b>비활성은 판매 중지가 아니다.</b> 상품이 실제로 파는 값은 {@code product_option_values} 가
 * 들고 있고 선택 검증도 그쪽 활성 여부를 본다. 여기서 값을 내리는 것은 "앞으로 이 값을 새로
 * 채택하지 말라"는 카탈로그 차원의 표시다.
 */
@Service
@Transactional(readOnly = true)
public class OptionCatalogAdminService implements ManageOptionCatalogUseCase {

    private final LoadOptionCatalogPort loadPort;
    private final SaveOptionCatalogPort savePort;

    public OptionCatalogAdminService(LoadOptionCatalogPort loadPort, SaveOptionCatalogPort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
    }

    @Override
    @Transactional
    public OptionAxis createAxis(String code, String name, OptionInputType inputType) {
        loadPort.findAxisByCode(code).ifPresent(existing -> {
            throw new ProductInvariantViolationException("이미 존재하는 옵션 축 코드입니다: " + code);
        });
        return savePort.saveAxis(OptionAxis.create(code, name, inputType));
    }

    @Override
    @Transactional
    public OptionAxis updateAxis(String code, String name, OptionInputType inputType) {
        OptionAxis axis = requireAxis(code);
        if (inputType.requiresSwatch()) {
            requireEveryValueHasSwatch(axis, code);
        }
        axis.rename(name);
        axis.changeInputType(inputType);
        return savePort.saveAxis(axis);
    }

    /**
     * SWATCH 로 바꾸려면 이미 달린 값이 전부 표시색을 갖고 있어야 한다. 뒤늦게 표현 방식만 바꾸면
     * 기존 값들이 조용히 빈 칩이 되는데, 그 시점에는 어느 값이 문제인지 화면에서 알 수 없다.
     */
    private void requireEveryValueHasSwatch(OptionAxis axis, String code) {
        String missing = loadPort.loadAxisValues(axis.getId()).stream()
                .filter(value -> value.getSwatchHex() == null)
                .map(OptionAxisValue::getCode)
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new ProductInvariantViolationException(
                    "표시색이 없는 값이 있어 " + code + " 축을 SWATCH 로 바꿀 수 없습니다: " + missing);
        }
    }

    @Override
    @Transactional
    public OptionAxis setAxisActive(String code, boolean active) {
        OptionAxis axis = requireAxis(code);
        if (active) {
            axis.activate();
        } else {
            axis.deactivate();
        }
        return savePort.saveAxis(axis);
    }

    @Override
    public List<OptionAxisValue> getValues(String axisCode) {
        return loadPort.loadAxisValues(requireAxis(axisCode).getId());
    }

    @Override
    @Transactional
    public OptionAxisValue addValue(String axisCode, String code, String name,
                                    String swatchHex, int sortOrder) {
        OptionAxis axis = requireAxis(axisCode);
        if (!axis.getInputType().hasEnumeratedValues()) {
            throw new ProductInvariantViolationException(
                    axis.getInputType() + " 축은 표준값 목록을 갖지 않습니다: " + axisCode);
        }
        loadPort.findAxisValueByCode(axis.getId(), code).ifPresent(existing -> {
            throw new ProductInvariantViolationException(
                    "이미 존재하는 옵션 값 코드입니다: " + axisCode + "=" + code);
        });
        OptionAxisValue value = OptionAxisValue.create(axis.getId(), code, name, swatchHex, sortOrder);
        requireSatisfiesAxis(value, axis);
        return savePort.saveAxisValue(value);
    }

    @Override
    @Transactional
    public OptionAxisValue updateValue(String axisCode, String valueCode, String name,
                                       String swatchHex, int sortOrder) {
        OptionAxis axis = requireAxis(axisCode);
        OptionAxisValue value = requireValue(axis, axisCode, valueCode);
        value.rename(name);
        value.changeSwatchHex(swatchHex);
        value.changeSortOrder(sortOrder);
        requireSatisfiesAxis(value, axis);
        return savePort.saveAxisValue(value);
    }

    private void requireSatisfiesAxis(OptionAxisValue value, OptionAxis axis) {
        if (!value.satisfies(axis)) {
            throw new ProductInvariantViolationException(
                    "SWATCH 축의 값에는 표시색(#RRGGBB)이 필요합니다: "
                            + axis.getCode() + "=" + value.getCode());
        }
    }

    @Override
    @Transactional
    public OptionAxisValue setValueActive(String axisCode, String valueCode, boolean active) {
        OptionAxis axis = requireAxis(axisCode);
        OptionAxisValue value = requireValue(axis, axisCode, valueCode);
        if (active) {
            value.activate();
        } else {
            value.deactivate();
        }
        return savePort.saveAxisValue(value);
    }

    private OptionAxis requireAxis(String code) {
        return loadPort.findAxisByCode(code)
                .orElseThrow(() -> new ProductNotFoundException("옵션 축을 찾을 수 없습니다: " + code));
    }

    private OptionAxisValue requireValue(OptionAxis axis, String axisCode, String valueCode) {
        return loadPort.findAxisValueByCode(axis.getId(), valueCode)
                .orElseThrow(() -> new ProductNotFoundException(
                        "옵션 값을 찾을 수 없습니다: " + axisCode + "=" + valueCode));
    }
}
