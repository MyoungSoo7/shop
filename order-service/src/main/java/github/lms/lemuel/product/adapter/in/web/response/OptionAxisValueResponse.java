package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.OptionAxisValue;

/**
 * 표준 옵션 값 응답.
 *
 * <p>{@code swatchHex} 는 SWATCH 축에서만 채워진다 — 값이 비어 있는데 축이 SWATCH 면 화면이
 * 빈 칩을 그리게 되므로, 운영 화면은 그 조합을 경고로 드러내야 한다.
 */
public record OptionAxisValueResponse(Long id,
                                      Long axisId,
                                      String code,
                                      String name,
                                      String swatchHex,
                                      int sortOrder,
                                      boolean active) {

    public static OptionAxisValueResponse from(OptionAxisValue value) {
        return new OptionAxisValueResponse(value.getId(), value.getAxisId(), value.getCode(),
                value.getName(), value.getSwatchHex(), value.getSortOrder(), value.isActive());
    }
}
