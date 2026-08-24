package github.lms.lemuel.category.adapter.out.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "display_section_items")
@IdClass(DisplaySectionItemJpaEntity.Id.class)
public class DisplaySectionItemJpaEntity {

    @jakarta.persistence.Id
    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @jakarta.persistence.Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_pinned", nullable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected DisplaySectionItemJpaEntity() { }

    public DisplaySectionItemJpaEntity(Long sectionId, Long productId, int sortOrder, boolean pinned) {
        this.sectionId = sectionId;
        this.productId = productId;
        this.sortOrder = sortOrder;
        this.pinned = pinned;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public void applyDomainState(int sortOrder, boolean pinned) {
        this.sortOrder = sortOrder;
        this.pinned = pinned;
    }

    public Long getSectionId() { return sectionId; }
    public Long getProductId() { return productId; }
    public int getSortOrder() { return sortOrder; }
    public boolean isPinned() { return pinned; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** 복합 키 — (편성, 상품). 같은 상품을 한 편성에 두 번 담을 수 없다. */
    public static class Id implements Serializable {
        private Long sectionId;
        private Long productId;

        public Id() { }

        public Id(Long sectionId, Long productId) {
            this.sectionId = sectionId;
            this.productId = productId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(sectionId, other.sectionId)
                    && Objects.equals(productId, other.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sectionId, productId);
        }
    }
}
