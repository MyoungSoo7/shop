package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataOrderStatusHistoryRepository
        extends JpaRepository<OrderStatusHistoryJpaEntity, Long> {

    /** 이 주문이 {@code newStatus} 로 들어온 가장 최근 기록. 신청 철회의 복귀 상태 근거. */
    Optional<OrderStatusHistoryJpaEntity> findTopByOrderIdAndNewStatusOrderByIdDesc(
            Long orderId, String newStatus);

    /**
     * 이 주문의 상태 변경 전부를 기록된 순서대로.
     *
     * <p>{@code changedAt} 이 아니라 {@code id} 로 정렬한다. 같은 트랜잭션 안에서 두 번 바뀌면
     * {@code LocalDateTime.now()} 가 같은 값으로 찍힐 수 있고, 그러면 정렬이 임의가 되어
     * <b>인과가 거꾸로 보인다</b>. id 는 삽입 순서를 그대로 보존한다.
     */
    List<OrderStatusHistoryJpaEntity> findByOrderIdOrderByIdAsc(Long orderId);
}
