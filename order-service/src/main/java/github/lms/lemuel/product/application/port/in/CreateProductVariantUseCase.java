package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.ProductVariant;

import java.math.BigDecimal;

public interface CreateProductVariantUseCase {

    ProductVariant create(Long productId, String sku, String optionName,
                          BigDecimal additionalPrice, int initialStock);
}
