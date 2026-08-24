package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductImage;

import java.util.List;
import java.util.Optional;

public interface LoadProductImagePort {

    Optional<ProductImage> findByIdNotDeleted(Long imageId);

    List<ProductImage> findByProductIdNotDeleted(Long productId);

    Optional<ProductImage> findPrimaryImageByProductId(Long productId);

    long countByProductIdNotDeleted(Long productId);
}
