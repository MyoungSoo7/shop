package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;

import java.util.List;
import java.util.Optional;

public interface LoadProductPort {

    Optional<Product> findById(Long productId);

    Optional<Product> findByName(String name);

    List<Product> findAll();

    List<Product> findByStatus(ProductStatus status);

    List<Product> findAvailableProducts();

    List<Product> search(String keyword, Long categoryId, String sortBy, String sortDirection);

    boolean existsByName(String name);
}
