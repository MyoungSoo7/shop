package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;

public interface ManageProductStatusUseCase {

    Product activateProduct(Long productId);

    Product deactivateProduct(Long productId);

    Product discontinueProduct(Long productId);
}
