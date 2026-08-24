package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataTagRepository extends JpaRepository<TagJpaEntity, Long> {

    Optional<TagJpaEntity> findByName(String name);

    @Query("SELECT t FROM TagJpaEntity t JOIN ProductTagJpaEntity pt ON t.id = pt.tagId WHERE pt.productId = :productId")
    List<TagJpaEntity> findByProductId(@Param("productId") Long productId);
}
