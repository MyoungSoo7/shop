package github.lms.lemuel.cart.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataCartRepository extends JpaRepository<CartJpaEntity, Long> {
    Optional<CartJpaEntity> findByUserId(Long userId);
}
