package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ManageVariantCostUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 매입가·마진.
 *
 * <p>마진을 계산하려면 판매가가 필요하고, 판매가는 SKU 혼자 모른다 — 기준가는 상품에 있다.
 * 그래서 목록을 만들 때 상품을 한 번만 읽어 모든 SKU 에 같은 기준가를 물린다.
 *
 * <p>SKU 가 경로의 상품 것인지 확인한 뒤에만 고친다. 관리자 전용 경로지만, 검증을 생략하면
 * 다른 상품의 SKU 원가를 이 상품 경로로 고칠 수 있고 그건 감사 로그를 거짓말로 만든다.
 */
@Service
@Transactional
public class ManageVariantCostService implements ManageVariantCostUseCase {

    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final SaveProductVariantPort saveVariantPort;

    public ManageVariantCostService(LoadProductPort loadProductPort,
                                     LoadProductVariantPort loadVariantPort,
                                     SaveProductVariantPort saveVariantPort) {
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.saveVariantPort = saveVariantPort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantCostView> listByProductId(Long productId) {
        BigDecimal basePrice = requireProduct(productId).getPrice();
        return loadVariantPort.loadByProductId(productId).stream()
                .map(v -> toView(v, basePrice))
                .toList();
    }

    @Override
    public VariantCostView changePurchasePrice(Long productId, Long variantId,
                                                BigDecimal purchasePrice) {
        BigDecimal basePrice = requireProduct(productId).getPrice();
        ProductVariant variant = loadVariantPort.loadById(variantId)
                .orElseThrow(() -> new ProductInvariantViolationException(
                        "옵션(SKU)을 찾을 수 없습니다: " + variantId));
        if (!variant.getProductId().equals(productId)) {
            throw new ProductInvariantViolationException(
                    "옵션(SKU) " + variantId + " 은 상품 " + productId + " 의 것이 아닙니다.");
        }
        variant.changePurchasePrice(purchasePrice);
        return toView(saveVariantPort.save(variant), basePrice);
    }

    private Product requireProduct(Long productId) {
        return loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private static VariantCostView toView(ProductVariant v, BigDecimal basePrice) {
        return new VariantCostView(
                v.getId(), v.getSku(), v.getOptionName(), v.getStockQuantity(),
                v.effectiveUnitPrice(basePrice), v.getPurchasePrice(),
                v.marginAmount(basePrice), v.marginRate(basePrice));
    }
}
