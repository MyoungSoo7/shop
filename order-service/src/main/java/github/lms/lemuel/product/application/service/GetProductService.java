package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.GetProductUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetProductService implements GetProductUseCase {

    private final LoadProductPort loadProductPort;

    @Override
    @Cacheable(value = "products", key = "#productId")
    public Product getProductById(Long productId) {
        log.info("상품 조회: productId={}", productId);
        return loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    @Override
    @Cacheable(value = "products", key = "'all'")
    public List<Product> getAllProducts() {
        log.info("전체 상품 조회");
        return loadProductPort.findAll();
    }

    @Override
    @Cacheable(value = "products", key = "'status:' + #status")
    public List<Product> getProductsByStatus(ProductStatus status) {
        log.info("상태별 상품 조회: status={}", status);
        return loadProductPort.findByStatus(status);
    }

    @Override
    @Cacheable(value = "products", key = "'available'")
    public List<Product> getAvailableProducts() {
        log.info("판매 가능한 상품 조회");
        return loadProductPort.findAvailableProducts();
    }

    @Override
    public List<Product> searchProducts(String keyword, Long categoryId, String sortBy, String sortDirection) {
        log.info("상품 검색: keyword={}, categoryId={}, sortBy={}, sortDirection={}",
                keyword, categoryId, sortBy, sortDirection);
        return loadProductPort.search(keyword, categoryId, sortBy, sortDirection);
    }
}
