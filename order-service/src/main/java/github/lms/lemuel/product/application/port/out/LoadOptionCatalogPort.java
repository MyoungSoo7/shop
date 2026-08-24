package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.ProductOptionAxis;
import github.lms.lemuel.product.domain.ProductOptionValue;

import java.util.List;
import java.util.Optional;

/**
 * 옵션 카탈로그 조회 포트 — 표준 축/값과 상품이 채택한 축/값.
 */
public interface LoadOptionCatalogPort {

    Optional<OptionAxis> findAxisByCode(String code);

    Optional<OptionAxis> findAxisById(Long axisId);

    List<OptionAxis> loadAllAxes();

    Optional<OptionAxisValue> findAxisValueByCode(Long axisId, String code);

    Optional<OptionAxisValue> findAxisValueById(Long axisValueId);

    List<OptionAxisValue> loadAxisValues(Long axisId);

    /** 차수(sort_order) 오름차순. */
    List<ProductOptionAxis> loadProductAxes(Long productId);

    Optional<ProductOptionAxis> findProductAxis(Long productId, Long axisId);

    Optional<ProductOptionAxis> findProductAxisById(Long productOptionAxisId);

    List<ProductOptionValue> loadProductValues(Long productOptionAxisId);

    Optional<ProductOptionValue> findProductValueById(Long productOptionValueId);

    Optional<ProductOptionValue> findProductValue(Long productOptionAxisId, Long axisValueId);
}
