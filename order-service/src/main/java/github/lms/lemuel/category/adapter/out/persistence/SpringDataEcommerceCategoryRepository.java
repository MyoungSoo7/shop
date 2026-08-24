package github.lms.lemuel.category.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;

public interface SpringDataEcommerceCategoryRepository extends JpaRepository<EcommerceCategoryJpaEntity, Long> {

    Optional<EcommerceCategoryJpaEntity> findBySlug(String slug);

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.deletedAt IS NULL ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findAllNotDeleted();

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.deletedAt IS NULL AND c.isActive = true ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findAllActiveNotDeleted();

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.parentId IS NULL AND c.deletedAt IS NULL ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findRootCategories();

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.parentId = :parentId AND c.deletedAt IS NULL ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findByParentId(@Param("parentId") Long parentId);

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<EcommerceCategoryJpaEntity> findByIdNotDeleted(@Param("id") Long id);

    @Query("SELECT COUNT(c) FROM EcommerceCategoryJpaEntity c WHERE c.parentId = :parentId AND c.deletedAt IS NULL")
    long countChildrenByParentId(@Param("parentId") Long parentId);

    @Query("SELECT CASE WHEN COUNT(pc) > 0 THEN true ELSE false END " +
           "FROM ProductEcommerceCategoryJpaEntity pc WHERE pc.categoryId = :categoryId")
    boolean hasProducts(@Param("categoryId") Long categoryId);

    /**
     * 하위 트리 전체(자기 포함). {@code path_ids @> ARRAY[:id]} 가 GIN 인덱스를 타므로
     * 재귀 CTE 없이 한 번의 스캔으로 끝난다.
     */
    @Query(value = """
            SELECT * FROM opslab.ecommerce_categories
            WHERE path_ids @> ARRAY[:categoryId]::bigint[]
              AND deleted_at IS NULL
            ORDER BY path_slug
            """, nativeQuery = true)
    List<EcommerceCategoryJpaEntity> findSubtree(@Param("categoryId") Long categoryId);

    /**
     * 경로 전량 재계산. 부분 갱신 대신 트리 전체를 다시 쓰는 이유: 부모 변경은 옮겨진 노드뿐 아니라
     * 그 아래 전부의 경로를 바꾸고, "어디까지가 영향 범위인가" 를 틀리면 경로가 조용히 어긋난다.
     * 카테고리 트리는 수백 행 규모라 전량 재계산이 더 싸고 확실하다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id, slug, parent_id, ARRAY[id]::BIGINT[] AS path_ids, slug::TEXT AS path_slug
                FROM opslab.ecommerce_categories WHERE parent_id IS NULL
                UNION ALL
                SELECT c.id, c.slug, c.parent_id, t.path_ids || c.id, t.path_slug || '/' || c.slug
                FROM opslab.ecommerce_categories c JOIN tree t ON c.parent_id = t.id
            )
            UPDATE opslab.ecommerce_categories e
            SET path_ids = t.path_ids, path_slug = t.path_slug
            FROM tree t
            WHERE e.id = t.id
              AND (e.path_ids IS DISTINCT FROM t.path_ids OR e.path_slug IS DISTINCT FROM t.path_slug)
            """, nativeQuery = true)
    int recalculatePaths();

    /** 상품수 캐시 재계산. 정본은 매핑 테이블이며 여기서는 그 실계수를 그대로 적는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE opslab.ecommerce_categories e
            SET product_count = (SELECT COUNT(*) FROM opslab.product_ecommerce_categories p
                                 WHERE p.category_id = e.id)
            WHERE e.product_count <> (SELECT COUNT(*) FROM opslab.product_ecommerce_categories p
                                      WHERE p.category_id = e.id)
            """, nativeQuery = true)
    int refreshProductCounts();

    /**
     * 상품수 캐시가 어긋난 카테고리 수.
     *
     * <p>조건은 {@link #refreshProductCounts()} 의 WHERE 절과 <b>같은 식</b>이다. 갈리면 점검은
     * 깨끗한데 재계산이 행을 고치는 상태가 되고, 그때는 어느 쪽을 믿어야 할지 알 수 없다.
     * (삭제 카테고리를 빼지 않는 것도 재계산과 맞춘 것이다 — 캐시는 그 행에도 남아 있다.)
     */
    @Query(value = """
            SELECT COUNT(*) FROM opslab.ecommerce_categories e
            WHERE e.product_count <> (SELECT COUNT(*) FROM opslab.product_ecommerce_categories p
                                      WHERE p.category_id = e.id)
            """, nativeQuery = true)
    long countProductCountDrifts();

    /** 어긋난 카테고리 표본 — 차이가 큰 순(같으면 id 순)이라 조치 우선순위대로 읽힌다. */
    @Query(value = """
            SELECT e.id, e.slug, e.name, e.product_count,
                   (SELECT COUNT(*) FROM opslab.product_ecommerce_categories p
                    WHERE p.category_id = e.id) AS actual_count
            FROM opslab.ecommerce_categories e
            WHERE e.product_count <> (SELECT COUNT(*) FROM opslab.product_ecommerce_categories p
                                      WHERE p.category_id = e.id)
            ORDER BY ABS(e.product_count - (SELECT COUNT(*) FROM opslab.product_ecommerce_categories p
                                            WHERE p.category_id = e.id)) DESC, e.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findProductCountDrifts(@Param("limit") int limit);
}
