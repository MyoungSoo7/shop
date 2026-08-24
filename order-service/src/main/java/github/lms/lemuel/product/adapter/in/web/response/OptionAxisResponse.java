package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionInputType;

/**
 * 표준 옵션 축 응답.
 */
public record OptionAxisResponse(Long id,
                                 String code,
                                 String name,
                                 OptionInputType inputType,
                                 boolean active) {

    public static OptionAxisResponse from(OptionAxis axis) {
        return new OptionAxisResponse(axis.getId(), axis.getCode(), axis.getName(),
                axis.getInputType(), axis.isActive());
    }
}
