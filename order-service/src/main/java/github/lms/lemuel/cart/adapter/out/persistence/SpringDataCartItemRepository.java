package github.lms.lemuel.cart.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SpringDataCartItemRepository extends JpaRepository<CartItemJpaEntity, Long> {

    List<CartItemJpaEntity> findByCartIdOrderByAddedAtAsc(Long cartId);

    @Transactional
    void deleteByCartId(Long cartId);
}
