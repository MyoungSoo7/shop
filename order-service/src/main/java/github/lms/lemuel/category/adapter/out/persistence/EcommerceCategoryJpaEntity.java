package github.lms.lemuel.category.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ecommerce_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EcommerceCategoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 300)
    private String slug;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false)
    private Integer depth;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 루트→자기 slug 경로. path_ids(BIGINT[]) 는 JPA 로 매핑하지 않는다 — 하위 트리 조회는
     * GIN 인덱스를 타는 네이티브 질의가 담당하고, 매핑하지 않은 컬럼은 스키마 검증에도 영향이 없다.
     */
    @Column(name = "path_slug", length = 900)
    private String pathSlug;

    /** 직접 매핑된 상품 수 캐시. 정본은 product_ecommerce_categories 실계수. */
    @Column(name = "product_count", nullable = false)
    private Integer productCount;

    /**
     * 경로·카운트는 DB 가 유지하는 파생값이라 도메인에서 넘어오지 않는다. 기존 호출부의
     * 10-인자 형태를 그대로 유지해, 파생 컬럼 추가가 매퍼 시그니처를 흔들지 않게 한다.
     */
    public EcommerceCategoryJpaEntity(Long id, String name, String slug, Long parentId,
                                      Integer depth, Integer sortOrder, Boolean isActive,
                                      LocalDateTime createdAt, LocalDateTime updatedAt,
                                      LocalDateTime deletedAt) {
        this(id, name, slug, parentId, depth, sortOrder, isActive,
                createdAt, updatedAt, deletedAt, null, 0);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (depth == null) {
            depth = 0;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (isActive == null) {
            isActive = true;
        }
        if (productCount == null) {
            productCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
