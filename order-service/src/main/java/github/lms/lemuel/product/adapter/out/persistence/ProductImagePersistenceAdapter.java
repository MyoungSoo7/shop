package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.SaveProductImagePort;
import github.lms.lemuel.product.domain.ProductImage;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ProductImagePersistenceAdapter
        implements LoadProductImagePort, SaveProductImagePort {

    private final SpringDataProductImageRepository repository;
    private final ProductImageMapper mapper;

    public ProductImagePersistenceAdapter(SpringDataProductImageRepository repository,
                                          ProductImageMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<ProductImage> findByIdNotDeleted(Long imageId) {
        return repository.findByIdNotDeleted(imageId).map(mapper::toDomainEntity);
    }

    @Override
    public List<ProductImage> findByProductIdNotDeleted(Long productId) {
        return repository.findByProductIdNotDeleted(productId).stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public Optional<ProductImage> findPrimaryImageByProductId(Long productId) {
        return repository.findPrimaryImageByProductId(productId).map(mapper::toDomainEntity);
    }

    @Override
    public long countByProductIdNotDeleted(Long productId) {
        return repository.countByProductIdNotDeleted(productId);
    }

    @Override
    public ProductImage save(ProductImage image) {
        ProductImageJpaEntity saved = repository.save(mapper.toJpaEntity(image));
        return mapper.toDomainEntity(saved);
    }
}
