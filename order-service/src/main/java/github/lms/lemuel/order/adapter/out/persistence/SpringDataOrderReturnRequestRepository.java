package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataOrderReturnRequestRepository
        extends JpaRepository<OrderReturnRequestJpaEntity, Long> {

    @Query("""
            SELECT r FROM OrderReturnRequestJpaEntity r
            WHERE r.orderId = :orderId AND r.status IN ('REQUESTED', 'APPROVED', 'COLLECTED')
            """)
    Optional<OrderReturnRequestJpaEntity> findOpenByOrderId(@Param("orderId") Long orderId);

    List<OrderReturnRequestJpaEntity> findByOrderIdOrderByIdDesc(Long orderId);

    List<OrderReturnRequestJpaEntity> findByStatusInOrderByRequestedAtAsc(
            Collection<String> statuses, Pageable pageable);
}
