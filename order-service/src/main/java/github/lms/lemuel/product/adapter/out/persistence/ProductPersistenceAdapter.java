package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadPrimaryCategoryPort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ProductPersistenceAdapter implements LoadProductPort, SaveProductPort {

    private final SpringDataProductJpaRepository repository;
    private final LoadPrimaryCategoryPort loadPrimaryCategoryPort;
    private final ProductPersistenceMapper mapper;

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity = mapper.toEntity(product);
        ProductJpaEntity savedEntity = repository.save(entity);
        return withPrimaryCategory(mapper.toDomain(savedEntity));
    }

    @Override
    public Optional<Product> findById(Long productId) {
        return repository.findById(productId)
                .map(mapper::toDomain)
                .map(this::withPrimaryCategory);
    }

    @Override
    public Optional<Product> findByName(String name) {
        return repository.findByName(name)
                .map(mapper::toDomain)
                .map(this::withPrimaryCategory);
    }

    @Override
    public List<Product> findAll() {
        return withPrimaryCategories(repository.findAll().stream()
                .map(mapper::toDomain)
                .toList());
    }

    @Override
    public List<Product> findByStatus(ProductStatus status) {
        return withPrimaryCategories(repository.findByStatus(status).stream()
                .map(mapper::toDomain)
                .toList());
    }

    @Override
    public List<Product> findAvailableProducts() {
        return withPrimaryCategories(repository.findAvailableProducts().stream()
                .map(mapper::toDomain)
                .toList());
    }

    @Override
    public List<Product> search(String keyword, Long categoryId, String sortBy, String sortDirection) {
        Comparator<Product> comparator = comparator(sortBy);
        if ("DESC".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return withPrimaryCategories(repository.search(normalizedKeyword, categoryId).stream()
                .map(mapper::toDomain)
                .sorted(comparator)
                .toList());
    }

    /**
     * 대표 분류 주입 — 정본이 {@code products.category_id} 에서
     * {@code product_ecommerce_categories.is_primary} 로 옮겨졌기 때문에(Phase 5) 로드 시 채워 준다.
     */
    private Product withPrimaryCategory(Product product) {
        if (product == null) {
            return null;
        }
        return rehydrateWithCategory(product,
                loadPrimaryCategoryPort.findPrimaryCategoryId(product.getId()).orElse(null));
    }

    /** 목록 경로 전용 일괄 주입 — 상품마다 한 번씩 묻지 않는다(N+1 방지). */
    private List<Product> withPrimaryCategories(List<Product> products) {
        if (products.isEmpty()) {
            return products;
        }
        Map<Long, Long> primaryByProduct = loadPrimaryCategoryPort.findPrimaryCategoryIds(
                products.stream().map(Product::getId).toList());
        return products.stream()
                .map(p -> rehydrateWithCategory(p, primaryByProduct.get(p.getId())))
                .collect(Collectors.toList());
    }

    private static Product rehydrateWithCategory(Product product, Long categoryId) {
        if (categoryId == null) {
            return product;
        }
        return Product.rehydrate(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStockQuantity(), product.getStatus(), categoryId,
                product.getTagIds(), product.getOptionsJson(),
                product.getCreatedAt(), product.getUpdatedAt());
    }

    @Override
    public int decreaseStockIfAvailable(Long productId, int quantity) {
        return repository.decreaseStockIfAvailable(productId, quantity, LocalDateTime.now());
    }

    @Override
    public int increaseStock(Long productId, int quantity) {
        return repository.increaseStock(productId, quantity, LocalDateTime.now());
    }

    @Override
    public boolean existsByName(String name) {
        return repository.existsByName(name);
    }

    private static Comparator<Product> comparator(String sortBy) {
        return switch (sortBy == null ? "" : sortBy) {
            case "price" -> Comparator.comparing(Product::getPrice);
            case "latest", "createdAt" -> Comparator.comparing(Product::getCreatedAt);
            case "name" -> Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(Product::getId);
        };
    }
}
