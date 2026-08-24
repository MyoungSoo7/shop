package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Tag;

import java.util.List;
import java.util.Optional;

public interface LoadTagPort {
    Optional<Tag> findById(Long id);
    Optional<Tag> findByName(String name);
    List<Tag> findAll();
    List<Tag> findByProductId(Long productId);
}
