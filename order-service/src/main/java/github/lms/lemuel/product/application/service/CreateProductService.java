package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.CreateProductUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.PublishProductEventPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.DuplicateProductNameException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateProductService implements CreateProductUseCase {

    private final LoadProductPort loadProductPort;
    private final SaveProductPort saveProductPort;
    private final PublishProductEventPort publishProductEventPort;

    @Override
    @CacheEvict(value = "products", allEntries = true)
    public Product createProduct(CreateProductCommand command) {
        log.info("상품 생성 시작: name={}, price={}, stock={}",
                command.name(), command.price(), command.stockQuantity());

        // 1. 상품명 중복 확인
        if (loadProductPort.existsByName(command.name())) {
            log.warn("중복된 상품명: name={}", command.name());
            throw new DuplicateProductNameException(command.name());
        }

        // 2. Product 도메인 생성 (도메인 검증 수행) — 옵션 트리(JSON) 포함
        Product product = Product.create(
                command.name(),
                command.description(),
                command.price(),
                command.stockQuantity(),
                command.optionsJson()
        );

        // 3. 저장
        Product savedProduct = saveProductPort.save(product);

        // ADR 0020 Phase 3b — settlement product 프로젝션(name) 동기화용 ProductChanged 발행
        publishProductEventPort.publishProductChanged(savedProduct.getId(), savedProduct.getName());

        log.info("상품 생성 완료: productId={}, name={}", savedProduct.getId(), savedProduct.getName());

        return savedProduct;
    }
}
