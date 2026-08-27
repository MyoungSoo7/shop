package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.SaveProductImagePort;
import github.lms.lemuel.product.domain.ProductImage;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public Map<Long, ProductImage> findPrimaryImagesByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductImage> byProductId = new LinkedHashMap<>();
        for (ProductImageJpaEntity entity : repository.findPrimaryImagesByProductIds(productIds)) {
            // 대표 이미지는 상품당 하나여야 하지만 그 규칙을 강제하는 제약이 없다.
            // 둘 이상 나오면 먼저 온 것을 쓴다 — 여기서 예외를 던지면 이미지 데이터 한 건 때문에
            // 찜 목록 전체가 열리지 않는다.
            byProductId.putIfAbsent(entity.getProductId(), mapper.toDomainEntity(entity));
        }
        return byProductId;
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
