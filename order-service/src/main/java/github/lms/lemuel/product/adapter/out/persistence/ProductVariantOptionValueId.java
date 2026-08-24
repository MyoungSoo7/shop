package github.lms.lemuel.product.adapter.out.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link ProductVariantOptionValueJpaEntity} 복합 키 — (SKU, 상품 옵션 축).
 *
 * <p>축을 키에 포함하는 것이 곧 "축당 값 1 개" 불변식이다.
 */
public class ProductVariantOptionValueId implements Serializable {

    private Long variantId;
    private Long productOptionAxisId;

    public ProductVariantOptionValueId() { }

    public ProductVariantOptionValueId(Long variantId, Long productOptionAxisId) {
        this.variantId = variantId;
        this.productOptionAxisId = productOptionAxisId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductVariantOptionValueId other)) return false;
        return Objects.equals(variantId, other.variantId)
                && Objects.equals(productOptionAxisId, other.productOptionAxisId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variantId, productOptionAxisId);
    }
}
