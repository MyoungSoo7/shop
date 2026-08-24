package github.lms.lemuel.product.domain;

import java.util.Objects;

/**
 * SKU 조합이 어떤 옵션 값을 골랐는지 나타내는 매핑 한 줄.
 *
 * <p>{@code (variantId, productOptionAxisId)} 가 PK 라 <b>한 SKU 가 같은 축에 두 값을 가질 수 없다</b> —
 * "빨강이면서 파랑인 SKU" 를 DB 가 거절한다. 이 매핑이 있어야 "색상=빨강인 판매중 SKU" 같은 조회가
 * 문자열 LIKE 가 아니라 조인으로 성립한다.
 */
public final class ProductVariantOptionValue {

    private final Long variantId;
    private final Long productOptionAxisId;
    private final Long productOptionValueId;

    private ProductVariantOptionValue(Long variantId, Long productOptionAxisId,
                                      Long productOptionValueId) {
        this.variantId = variantId;
        this.productOptionAxisId = productOptionAxisId;
        this.productOptionValueId = productOptionValueId;
    }

    public static ProductVariantOptionValue of(Long variantId, Long productOptionAxisId,
                                               Long productOptionValueId) {
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(productOptionAxisId, "productOptionAxisId");
        Objects.requireNonNull(productOptionValueId, "productOptionValueId");
        return new ProductVariantOptionValue(variantId, productOptionAxisId, productOptionValueId);
    }

    public Long getVariantId() { return variantId; }
    public Long getProductOptionAxisId() { return productOptionAxisId; }
    public Long getProductOptionValueId() { return productOptionValueId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductVariantOptionValue other)) return false;
        return variantId.equals(other.variantId)
                && productOptionAxisId.equals(other.productOptionAxisId)
                && productOptionValueId.equals(other.productOptionValueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variantId, productOptionAxisId, productOptionValueId);
    }

    @Override
    public String toString() {
        return "ProductVariantOptionValue{variantId=" + variantId
                + ", axis=" + productOptionAxisId + ", value=" + productOptionValueId + '}';
    }
}
