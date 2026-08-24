package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataProductImageRepository extends JpaRepository<ProductImageJpaEntity, Long> {

    @Query("SELECT i FROM ProductImageJpaEntity i WHERE i.productId = :productId AND i.deletedAt IS NULL ORDER BY i.orderIndex ASC")
    List<ProductImageJpaEntity> findByProductIdNotDeleted(@Param("productId") Long productId);

    @Query("SELECT i FROM ProductImageJpaEntity i WHERE i.productId = :productId AND i.isPrimary = true AND i.deletedAt IS NULL")
    Optional<ProductImageJpaEntity> findPrimaryImageByProductId(@Param("productId") Long productId);

    @Query("SELECT i FROM ProductImageJpaEntity i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<ProductImageJpaEntity> findByIdNotDeleted(@Param("id") Long id);

    @Query("SELECT COUNT(i) FROM ProductImageJpaEntity i WHERE i.productId = :productId AND i.deletedAt IS NULL")
    long countByProductIdNotDeleted(@Param("productId") Long productId);
}
