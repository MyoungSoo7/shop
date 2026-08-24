package github.lms.lemuel.product.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_variant_option_values")
@IdClass(ProductVariantOptionValueId.class)
public class ProductVariantOptionValueJpaEntity {

    @Id
    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Id
    @Column(name = "product_option_axis_id", nullable = false)
    private Long productOptionAxisId;

    @Column(name = "product_option_value_id", nullable = false)
    private Long productOptionValueId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ProductVariantOptionValueJpaEntity() { }

    public ProductVariantOptionValueJpaEntity(Long variantId, Long productOptionAxisId,
                                              Long productOptionValueId) {
        this.variantId = variantId;
        this.productOptionAxisId = productOptionAxisId;
        this.productOptionValueId = productOptionValueId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public void applyValue(Long productOptionValueId) {
        this.productOptionValueId = productOptionValueId;
    }

    public Long getVariantId() { return variantId; }
    public Long getProductOptionAxisId() { return productOptionAxisId; }
    public Long getProductOptionValueId() { return productOptionValueId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
