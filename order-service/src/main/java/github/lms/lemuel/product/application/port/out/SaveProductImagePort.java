package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductImage;

public interface SaveProductImagePort {

    ProductImage save(ProductImage image);
}
