package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataOrderStatusHistoryRepository
        extends JpaRepository<OrderStatusHistoryJpaEntity, Long> {

    /** 이 주문이 {@code newStatus} 로 들어온 가장 최근 기록. 신청 철회의 복귀 상태 근거. */
    Optional<OrderStatusHistoryJpaEntity> findTopByOrderIdAndNewStatusOrderByIdDesc(
            Long orderId, String newStatus);
}
