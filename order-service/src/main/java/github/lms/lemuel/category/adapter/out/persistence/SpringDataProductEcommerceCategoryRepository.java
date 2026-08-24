package github.lms.lemuel.category.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 상품 ↔ 이커머스 카테고리 매핑 리포지토리.
 *
 * <p>대표 분류 조회가 목적이다. 폐기된 {@code products.category_id} 를 대신하므로 목록 경로에서
 * 상품마다 한 번씩 묻지 않도록 <b>일괄 조회</b>를 함께 제공한다(N+1 방지).
 */
public interface SpringDataProductEcommerceCategoryRepository
        extends JpaRepository<ProductEcommerceCategoryJpaEntity, ProductEcommerceCategoryId> {

    Optional<ProductEcommerceCategoryJpaEntity> findByProductIdAndPrimaryTrue(Long productId);

    @Query("SELECT pc FROM ProductEcommerceCategoryJpaEntity pc "
            + "WHERE pc.productId IN :productIds AND pc.primary = TRUE")
    List<ProductEcommerceCategoryJpaEntity> findPrimaryByProductIds(
            @Param("productIds") Collection<Long> productIds);
}
