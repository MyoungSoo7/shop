package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.SaveOptionCatalogPort;
import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.OptionInputType;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionCatalogAdminServiceTest {

    @Mock LoadOptionCatalogPort loadPort;
    @Mock SaveOptionCatalogPort savePort;
    @InjectMocks OptionCatalogAdminService service;

    private OptionAxis axis(Long id, String code, OptionInputType type) {
        return OptionAxis.rehydrate(id, code, "축 " + code, type, true);
    }

    private OptionAxisValue value(Long id, Long axisId, String code, String swatchHex) {
        return OptionAxisValue.rehydrate(id, axisId, code, "값 " + code, swatchHex, 0, true);
    }

    // ── 축 ────────────────────────────────────────────────

    @Test
    @DisplayName("새 축을 만든다 — 활성 상태로 태어난다")
    void createAxis() {
        when(loadPort.findAxisByCode("CAPACITY")).thenReturn(Optional.empty());
        when(savePort.saveAxis(any())).thenAnswer(inv -> inv.getArgument(0));

        OptionAxis created = service.createAxis("CAPACITY", "용량", OptionInputType.SELECT);

        assertThat(created.getCode()).isEqualTo("CAPACITY");
        assertThat(created.getName()).isEqualTo("용량");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    @DisplayName("같은 코드의 축을 두 벌 만들지 않는다 — 축이 갈라지면 파셋도 갈라진다")
    void createAxisRejectsDuplicateCode() {
        when(loadPort.findAxisByCode("COLOR")).thenReturn(Optional.of(axis(1L, "COLOR", OptionInputType.SWATCH)));

        assertThatThrownBy(() -> service.createAxis("COLOR", "색깔", OptionInputType.SELECT))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("COLOR");

        verify(savePort, never()).saveAxis(any());
    }

    @Test
    @DisplayName("축의 표시 이름을 바꾼다 — 코드는 그대로라 SKU 매핑은 흔들리지 않는다")
    void updateAxisRenames() {
        when(loadPort.findAxisByCode("COLOR")).thenReturn(Optional.of(axis(1L, "COLOR", OptionInputType.SWATCH)));
        when(loadPort.loadAxisValues(1L)).thenReturn(List.of(value(11L, 1L, "RED", "#FF0000")));
        when(savePort.saveAxis(any())).thenAnswer(inv -> inv.getArgument(0));

        OptionAxis updated = service.updateAxis("COLOR", "컬러", OptionInputType.SWATCH);

        assertThat(updated.getCode()).isEqualTo("COLOR");
        assertThat(updated.getName()).isEqualTo("컬러");
    }

    @Test
    @DisplayName("표시색 없는 값이 있는 축을 SWATCH 로 바꾸지 않는다 — 화면이 빈 칩을 그리게 된다")
    void updateAxisRejectsSwatchWhenValuesLackColor() {
        when(loadPort.findAxisByCode("SIZE")).thenReturn(Optional.of(axis(2L, "SIZE", OptionInputType.SELECT)));
        when(loadPort.loadAxisValues(2L)).thenReturn(List.of(
                value(21L, 2L, "L", null),
                value(22L, 2L, "XL", null)));

        assertThatThrownBy(() -> service.updateAxis("SIZE", "사이즈", OptionInputType.SWATCH))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("L")
                .hasMessageContaining("XL");

        verify(savePort, never()).saveAxis(any());
    }

    @Test
    @DisplayName("축을 내린다 — 카탈로그에서 감추는 표시일 뿐 이미 파는 상품은 멈추지 않는다")
    void deactivateAxis() {
        when(loadPort.findAxisByCode("COLOR")).thenReturn(Optional.of(axis(1L, "COLOR", OptionInputType.SWATCH)));
        when(savePort.saveAxis(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.setAxisActive("COLOR", false).isActive()).isFalse();
    }

    @Test
    @DisplayName("없는 축 코드는 404 다")
    void unknownAxis() {
        when(loadPort.findAxisByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setAxisActive("NOPE", true))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // ── 값 ────────────────────────────────────────────────

    @Test
    @DisplayName("축에 표준 값을 더한다")
    void addValue() {
        when(loadPort.findAxisByCode("SIZE")).thenReturn(Optional.of(axis(2L, "SIZE", OptionInputType.SELECT)));
        when(loadPort.findAxisValueByCode(2L, "XL")).thenReturn(Optional.empty());
        when(savePort.saveAxisValue(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<OptionAxisValue> captor = ArgumentCaptor.forClass(OptionAxisValue.class);
        service.addValue("SIZE", "XL", "특대", null, 3);

        verify(savePort).saveAxisValue(captor.capture());
        assertThat(captor.getValue().getAxisId()).isEqualTo(2L);
        assertThat(captor.getValue().getCode()).isEqualTo("XL");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 축에 같은 코드의 값을 두 번 넣지 않는다")
    void addValueRejectsDuplicate() {
        when(loadPort.findAxisByCode("SIZE")).thenReturn(Optional.of(axis(2L, "SIZE", OptionInputType.SELECT)));
        when(loadPort.findAxisValueByCode(2L, "L")).thenReturn(Optional.of(value(21L, 2L, "L", null)));

        assertThatThrownBy(() -> service.addValue("SIZE", "L", "라지", null, 1))
                .isInstanceOf(ProductInvariantViolationException.class);

        verify(savePort, never()).saveAxisValue(any());
    }

    @Test
    @DisplayName("SWATCH 축의 값은 표시색 없이 들어갈 수 없다")
    void addValueRequiresSwatchOnSwatchAxis() {
        when(loadPort.findAxisByCode("COLOR")).thenReturn(Optional.of(axis(1L, "COLOR", OptionInputType.SWATCH)));
        when(loadPort.findAxisValueByCode(1L, "RED")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addValue("COLOR", "RED", "빨강", null, 0))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("표시색");

        verify(savePort, never()).saveAxisValue(any());
    }

    @Test
    @DisplayName("TEXT 축은 표준값 목록을 갖지 않는다 — 구매자가 직접 적는 축이다")
    void addValueRejectedOnTextAxis() {
        when(loadPort.findAxisByCode("ENGRAVING")).thenReturn(Optional.of(axis(3L, "ENGRAVING", OptionInputType.TEXT)));

        assertThatThrownBy(() -> service.addValue("ENGRAVING", "HELLO", "안녕", null, 0))
                .isInstanceOf(ProductInvariantViolationException.class);

        verify(savePort, never()).saveAxisValue(any());
    }

    @Test
    @DisplayName("값의 이름·표시색·정렬을 바꾼다 — 코드는 그대로다")
    void updateValue() {
        when(loadPort.findAxisByCode("COLOR")).thenReturn(Optional.of(axis(1L, "COLOR", OptionInputType.SWATCH)));
        when(loadPort.findAxisValueByCode(1L, "RED")).thenReturn(Optional.of(value(11L, 1L, "RED", "#FF0000")));
        when(savePort.saveAxisValue(any())).thenAnswer(inv -> inv.getArgument(0));

        OptionAxisValue updated = service.updateValue("COLOR", "RED", "진빨강", "#CC0000", 2);

        assertThat(updated.getCode()).isEqualTo("RED");
        assertThat(updated.getName()).isEqualTo("진빨강");
        assertThat(updated.getSwatchHex()).isEqualTo("#CC0000");
        assertThat(updated.getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("SWATCH 축의 값에서 표시색을 지울 수 없다")
    void updateValueCannotClearSwatchOnSwatchAxis() {
        when(loadPort.findAxisByCode("COLOR")).thenReturn(Optional.of(axis(1L, "COLOR", OptionInputType.SWATCH)));
        when(loadPort.findAxisValueByCode(1L, "RED")).thenReturn(Optional.of(value(11L, 1L, "RED", "#FF0000")));

        assertThatThrownBy(() -> service.updateValue("COLOR", "RED", "빨강", null, 0))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("표시색");

        verify(savePort, never()).saveAxisValue(any());
    }

    @Test
    @DisplayName("값을 내린다")
    void deactivateValue() {
        when(loadPort.findAxisByCode("SIZE")).thenReturn(Optional.of(axis(2L, "SIZE", OptionInputType.SELECT)));
        when(loadPort.findAxisValueByCode(2L, "L")).thenReturn(Optional.of(value(21L, 2L, "L", null)));
        when(savePort.saveAxisValue(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.setValueActive("SIZE", "L", false).isActive()).isFalse();
    }

    @Test
    @DisplayName("없는 값 코드는 404 다")
    void unknownValue() {
        when(loadPort.findAxisByCode("SIZE")).thenReturn(Optional.of(axis(2L, "SIZE", OptionInputType.SELECT)));
        when(loadPort.findAxisValueByCode(2L, "NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setValueActive("SIZE", "NOPE", true))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("값 목록은 축의 정렬 순서 그대로 준다")
    void getValues() {
        when(loadPort.findAxisByCode("SIZE")).thenReturn(Optional.of(axis(2L, "SIZE", OptionInputType.SELECT)));
        when(loadPort.loadAxisValues(2L)).thenReturn(List.of(
                value(21L, 2L, "S", null), value(22L, 2L, "M", null)));

        assertThat(service.getValues("SIZE"))
                .extracting(OptionAxisValue::getCode)
                .containsExactly("S", "M");
    }
}
