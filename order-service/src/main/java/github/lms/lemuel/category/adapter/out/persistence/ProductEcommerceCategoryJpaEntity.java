package github.lms.lemuel.category.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_ecommerce_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@IdClass(ProductEcommerceCategoryId.class)
public class ProductEcommerceCategoryJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    /** 대표(주) 분류 여부. 상품당 최대 1 행 — 부분 유니크 인덱스가 강제한다. */
    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
