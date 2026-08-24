package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ManageProductStatusUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ManageProductStatusService implements ManageProductStatusUseCase {

    private final LoadProductPort loadProductPort;
    private final SaveProductPort saveProductPort;

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product activateProduct(Long productId) {
        log.info("상품 활성화: productId={}", productId);

        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        product.activate();
        Product updatedProduct = saveProductPort.save(product);

        log.info("상품 활성화 완료: productId={}, status={}",
                updatedProduct.getId(), updatedProduct.getStatus());
        return updatedProduct;
    }

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product deactivateProduct(Long productId) {
        log.info("상품 비활성화: productId={}", productId);

        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        product.deactivate();
        Product updatedProduct = saveProductPort.save(product);

        log.info("상품 비활성화 완료: productId={}, status={}",
                updatedProduct.getId(), updatedProduct.getStatus());
        return updatedProduct;
    }

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product discontinueProduct(Long productId) {
        log.info("상품 단종: productId={}", productId);

        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        product.discontinue();
        Product updatedProduct = saveProductPort.save(product);

        log.info("상품 단종 완료: productId={}, status={}",
                updatedProduct.getId(), updatedProduct.getStatus());
        return updatedProduct;
    }
}
