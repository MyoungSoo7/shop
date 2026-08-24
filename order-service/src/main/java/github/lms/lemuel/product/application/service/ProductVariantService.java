package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase;
import github.lms.lemuel.product.application.port.in.CreateProductVariantUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 생성.
 *
 * <p><b>생성 시점에 옵션 카탈로그도 함께 채운다.</b> 채우지 않으면 새 SKU 는 축·값·조합 서명이 없는 채로
 * 남아 카탈로그 조회 경로로는 영영 찾히지 않는다 — 백필이 과거 데이터를 한 번 정리해도, 쓰기 경로가
 * 계속 서명 없는 행을 만들면 "모든 SKU 가 서명을 갖는다" 는 상태에 도달할 수 없다.
 *
 * <p>카탈로그를 만드는 규칙은 백필 유스케이스가 이미 소유하므로 그대로 재사용한다. 생성 경로가
 * 자기만의 등록 로직을 따로 두면 두 규칙이 갈라지고, 그 순간 백필로 만든 코드와 생성으로 만든 코드가
 * 달라져 같은 옵션이 두 축으로 쪼개진다. 상품 단위 재실행이라 비용이 조금 더 들지만 멱등하고 결과가 같다.
 *
 * <p>등록 후에도 서명이 없으면 <b>생성을 실패시킨다</b>. 서명이 없다는 건 표시명을 축:값으로 해석할 수
 * 없거나 이미 같은 조합의 SKU 가 있다는 뜻이고, 둘 다 조용히 넘기면 팔 수 없는 SKU 가 만들어진다.
 */
@Service
@Transactional
public class ProductVariantService implements CreateProductVariantUseCase {

    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final SaveProductVariantPort saveVariantPort;
    private final BackfillOptionCatalogUseCase optionCatalogRegistrar;
    private final BackfillVariantSignatureUseCase signatureRegistrar;

    public ProductVariantService(LoadProductPort loadProductPort,
                                  LoadProductVariantPort loadVariantPort,
                                  SaveProductVariantPort saveVariantPort,
                                  BackfillOptionCatalogUseCase optionCatalogRegistrar,
                                  BackfillVariantSignatureUseCase signatureRegistrar) {
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.saveVariantPort = saveVariantPort;
        this.optionCatalogRegistrar = optionCatalogRegistrar;
        this.signatureRegistrar = signatureRegistrar;
    }

    @Override
    public ProductVariant create(Long productId, String sku, String optionName,
                                  BigDecimal additionalPrice, int initialStock) {
        // 1) 상품 존재 검증
        loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        // 2) SKU 중복 검증
        if (loadVariantPort.loadBySku(sku).isPresent()) {
            throw new ProductInvariantViolationException("이미 사용 중인 SKU 입니다: " + sku);
        }

        ProductVariant variant = ProductVariant.create(productId, sku, optionName,
                additionalPrice, initialStock);
        ProductVariant saved = saveVariantPort.save(variant);

        // 3) 옵션 카탈로그 등록 — 축·값 생성 후 매핑·서명 부여(백필과 같은 규칙, 멱등)
        optionCatalogRegistrar.backfillProduct(productId);
        BackfillVariantSignatureUseCase.SignatureBackfillReport report =
                signatureRegistrar.backfillProduct(productId);

        ProductVariant registered = loadVariantPort.loadById(saved.getId())
                .orElseThrow(() -> new IllegalStateException("방금 저장한 SKU 가 사라짐: " + sku));
        if (!registered.hasOptionSignature()) {
            throw new ProductInvariantViolationException(
                    "옵션 조합을 등록할 수 없는 SKU 입니다(표시명은 '축:값' 형식이어야 하고 조합이 중복되면 안 됩니다): "
                            + optionName + " — " + String.join(" / ", report.warnings()));
        }
        return registered;
    }

    @Transactional(readOnly = true)
    public List<ProductVariant> listByProductId(Long productId) {
        return loadVariantPort.loadByProductId(productId);
    }
}
