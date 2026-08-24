package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.SaveOptionCatalogPort;
import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.ProductOptionAxis;
import github.lms.lemuel.product.domain.ProductOptionValue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class OptionCatalogPersistenceAdapter
        implements LoadOptionCatalogPort, SaveOptionCatalogPort {

    private final SpringDataOptionAxisRepository axisRepository;
    private final SpringDataOptionAxisValueRepository axisValueRepository;
    private final SpringDataProductOptionAxisRepository productAxisRepository;
    private final SpringDataProductOptionValueRepository productValueRepository;

    public OptionCatalogPersistenceAdapter(SpringDataOptionAxisRepository axisRepository,
                                           SpringDataOptionAxisValueRepository axisValueRepository,
                                           SpringDataProductOptionAxisRepository productAxisRepository,
                                           SpringDataProductOptionValueRepository productValueRepository) {
        this.axisRepository = axisRepository;
        this.axisValueRepository = axisValueRepository;
        this.productAxisRepository = productAxisRepository;
        this.productValueRepository = productValueRepository;
    }

    @Override
    public Optional<OptionAxis> findAxisByCode(String code) {
        return axisRepository.findByCode(code).map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<OptionAxis> findAxisById(Long axisId) {
        return axisRepository.findById(axisId).map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public List<OptionAxis> loadAllAxes() {
        return axisRepository.findAllByOrderByCodeAsc().stream()
                .map(OptionCatalogPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<OptionAxisValue> findAxisValueByCode(Long axisId, String code) {
        return axisValueRepository.findByAxisIdAndCode(axisId, code)
                .map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<OptionAxisValue> findAxisValueById(Long axisValueId) {
        return axisValueRepository.findById(axisValueId)
                .map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public List<OptionAxisValue> loadAxisValues(Long axisId) {
        return axisValueRepository.findByAxisIdOrderBySortOrderAscIdAsc(axisId).stream()
                .map(OptionCatalogPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ProductOptionAxis> loadProductAxes(Long productId) {
        return productAxisRepository.findByProductIdOrderBySortOrderAsc(productId).stream()
                .map(OptionCatalogPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<ProductOptionAxis> findProductAxis(Long productId, Long axisId) {
        return productAxisRepository.findByProductIdAndAxisId(productId, axisId)
                .map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<ProductOptionAxis> findProductAxisById(Long productOptionAxisId) {
        return productAxisRepository.findById(productOptionAxisId)
                .map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<ProductOptionValue> findProductValueById(Long productOptionValueId) {
        return productValueRepository.findById(productOptionValueId)
                .map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public List<ProductOptionValue> loadProductValues(Long productOptionAxisId) {
        return productValueRepository
                .findByProductOptionAxisIdOrderBySortOrderAscIdAsc(productOptionAxisId).stream()
                .map(OptionCatalogPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<ProductOptionValue> findProductValue(Long productOptionAxisId, Long axisValueId) {
        return productValueRepository
                .findByProductOptionAxisIdAndAxisValueId(productOptionAxisId, axisValueId)
                .map(OptionCatalogPersistenceAdapter::toDomain);
    }

    @Override
    public OptionAxis saveAxis(OptionAxis axis) {
        OptionAxisJpaEntity entity;
        if (axis.getId() == null) {
            entity = new OptionAxisJpaEntity(null, axis.getCode(), axis.getName(),
                    axis.getInputType(), axis.isActive());
        } else {
            entity = axisRepository.findById(axis.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "OptionAxis 사라짐 (id=" + axis.getId() + ")"));
            entity.applyDomainState(axis.getName(), axis.getInputType(), axis.isActive());
        }
        return toDomain(axisRepository.save(entity));
    }

    @Override
    public OptionAxisValue saveAxisValue(OptionAxisValue value) {
        OptionAxisValueJpaEntity entity;
        if (value.getId() == null) {
            entity = new OptionAxisValueJpaEntity(null, value.getAxisId(), value.getCode(),
                    value.getName(), value.getSwatchHex(), value.getSortOrder(), value.isActive());
        } else {
            entity = axisValueRepository.findById(value.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "OptionAxisValue 사라짐 (id=" + value.getId() + ")"));
            entity.applyDomainState(value.getName(), value.getSwatchHex(),
                    value.getSortOrder(), value.isActive());
        }
        return toDomain(axisValueRepository.save(entity));
    }

    @Override
    public ProductOptionAxis saveProductAxis(ProductOptionAxis axis) {
        ProductOptionAxisJpaEntity entity;
        if (axis.getId() == null) {
            entity = new ProductOptionAxisJpaEntity(null, axis.getProductId(), axis.getAxisId(),
                    axis.getSortOrder(), axis.isRequired());
        } else {
            entity = productAxisRepository.findById(axis.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ProductOptionAxis 사라짐 (id=" + axis.getId() + ")"));
            entity.applyDomainState(axis.getSortOrder(), axis.isRequired());
        }
        return toDomain(productAxisRepository.save(entity));
    }

    @Override
    public ProductOptionValue saveProductValue(ProductOptionValue value) {
        ProductOptionValueJpaEntity entity;
        if (value.getId() == null) {
            entity = new ProductOptionValueJpaEntity(null, value.getProductOptionAxisId(),
                    value.getAxisValueId(), value.getSortOrder(), value.isActive());
        } else {
            entity = productValueRepository.findById(value.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ProductOptionValue 사라짐 (id=" + value.getId() + ")"));
            entity.applyDomainState(value.getSortOrder(), value.isActive());
        }
        return toDomain(productValueRepository.save(entity));
    }

    private static OptionAxis toDomain(OptionAxisJpaEntity e) {
        return OptionAxis.rehydrate(e.getId(), e.getCode(), e.getName(),
                e.getInputType(), e.isActive());
    }

    private static OptionAxisValue toDomain(OptionAxisValueJpaEntity e) {
        return OptionAxisValue.rehydrate(e.getId(), e.getAxisId(), e.getCode(), e.getName(),
                e.getSwatchHex(), e.getSortOrder(), e.isActive());
    }

    private static ProductOptionAxis toDomain(ProductOptionAxisJpaEntity e) {
        return ProductOptionAxis.rehydrate(e.getId(), e.getProductId(), e.getAxisId(),
                e.getSortOrder(), e.isRequired());
    }

    private static ProductOptionValue toDomain(ProductOptionValueJpaEntity e) {
        return ProductOptionValue.rehydrate(e.getId(), e.getProductOptionAxisId(),
                e.getAxisValueId(), e.getSortOrder(), e.isActive());
    }
}
