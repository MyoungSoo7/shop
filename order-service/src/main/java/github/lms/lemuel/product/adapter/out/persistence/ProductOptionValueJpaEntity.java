package github.lms.lemuel.product.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_option_values",
        uniqueConstraints = @UniqueConstraint(name = "uq_product_option_values",
                columnNames = {"product_option_axis_id", "axis_value_id"}))
public class ProductOptionValueJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_option_axis_id", nullable = false)
    private Long productOptionAxisId;

    @Column(name = "axis_value_id", nullable = false)
    private Long axisValueId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ProductOptionValueJpaEntity() { }

    public ProductOptionValueJpaEntity(Long id, Long productOptionAxisId, Long axisValueId,
                                       int sortOrder, boolean active) {
        this.id = id;
        this.productOptionAxisId = productOptionAxisId;
        this.axisValueId = axisValueId;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public void applyDomainState(int sortOrder, boolean active) {
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public Long getId() { return id; }
    public Long getProductOptionAxisId() { return productOptionAxisId; }
    public Long getAxisValueId() { return axisValueId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
