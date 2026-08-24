package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataOrderItemOptionRepository
        extends JpaRepository<OrderItemOptionJpaEntity, Long> {

    List<OrderItemOptionJpaEntity> findByOrderItemIdOrderByAxisSortOrderAsc(Long orderItemId);

    List<OrderItemOptionJpaEntity> findByOrderItemIdInOrderByOrderItemIdAscAxisSortOrderAsc(
            List<Long> orderItemIds);
}
