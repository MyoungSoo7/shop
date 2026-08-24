package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataProductTagRepository extends JpaRepository<ProductTagJpaEntity, ProductTagId> {

    List<ProductTagJpaEntity> findByProductId(Long productId);

    List<ProductTagJpaEntity> findByTagId(Long tagId);

    @Modifying
    @Query("DELETE FROM ProductTagJpaEntity pt WHERE pt.productId = :productId")
    void deleteByProductId(@Param("productId") Long productId);

    @Modifying
    @Query("DELETE FROM ProductTagJpaEntity pt WHERE pt.productId = :productId AND pt.tagId = :tagId")
    void deleteByProductIdAndTagId(@Param("productId") Long productId, @Param("tagId") Long tagId);
}
