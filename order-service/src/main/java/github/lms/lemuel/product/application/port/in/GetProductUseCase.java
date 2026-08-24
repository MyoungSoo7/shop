package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;

import java.util.List;

public interface GetProductUseCase {

    Product getProductById(Long productId);

    List<Product> getAllProducts();

    List<Product> getProductsByStatus(ProductStatus status);

    List<Product> getAvailableProducts();

    List<Product> searchProducts(String keyword, Long categoryId, String sortBy, String sortDirection);
}
