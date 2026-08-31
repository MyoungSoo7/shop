package github.lms.lemuel.product.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_option_axes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_product_option_axes",
                        columnNames = {"product_id", "axis_id"}),
                @UniqueConstraint(name = "uq_product_option_axes_order",
                        columnNames = {"product_id", "sort_order"})
        })
public class ProductOptionAxisJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "axis_id", nullable = false)
    private Long axisId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    /** TEXT 축에서 받을 최대 글자 수. NULL 이면 기본값을 쓴다 — 기존 축을 건드리지 않고 도입하기 위해서다. */
    @Column(name = "text_max_length")
    private Integer textMaxLength;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ProductOptionAxisJpaEntity() { }

    public ProductOptionAxisJpaEntity(Long id, Long productId, Long axisId,
                                      int sortOrder, boolean required) {
        this(id, productId, axisId, sortOrder, required, null);
    }

    public ProductOptionAxisJpaEntity(Long id, Long productId, Long axisId,
                                      int sortOrder, boolean required, Integer textMaxLength) {
        this.textMaxLength = textMaxLength;
        this.id = id;
        this.productId = productId;
        this.axisId = axisId;
        this.sortOrder = sortOrder;
        this.required = required;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public void applyDomainState(int sortOrder, boolean required, Integer textMaxLength) {
        this.textMaxLength = textMaxLength;
        applyDomainState(sortOrder, required);
    }

    public void applyDomainState(int sortOrder, boolean required) {
        this.sortOrder = sortOrder;
        this.required = required;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getAxisId() { return axisId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isRequired() { return required; }
    public Integer getTextMaxLength() { return textMaxLength; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
