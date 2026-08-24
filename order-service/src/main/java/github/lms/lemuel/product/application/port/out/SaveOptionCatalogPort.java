package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.ProductOptionAxis;
import github.lms.lemuel.product.domain.ProductOptionValue;

/**
 * 옵션 카탈로그 저장 포트. 각 메서드는 DB 부여 PK 가 채워진 도메인 객체를 돌려준다.
 */
public interface SaveOptionCatalogPort {

    OptionAxis saveAxis(OptionAxis axis);

    OptionAxisValue saveAxisValue(OptionAxisValue value);

    ProductOptionAxis saveProductAxis(ProductOptionAxis axis);

    ProductOptionValue saveProductValue(ProductOptionValue value);
}
