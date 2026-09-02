package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.OrderStatusChange;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class OrderStatusHistoryPersistenceAdapter
        implements SaveOrderStatusHistoryPort, LoadOrderStatusHistoryPort {

    private final SpringDataOrderStatusHistoryRepository repository;

    public OrderStatusHistoryPersistenceAdapter(SpringDataOrderStatusHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Long orderId, String previousStatus, String newStatus, String changedBy, String reason) {
        repository.save(new OrderStatusHistoryJpaEntity(orderId, previousStatus, newStatus, changedBy, reason));
    }

    @Override
    public Optional<OrderStatus> findPreviousStatus(Long orderId, OrderStatus currentStatus) {
        return repository.findTopByOrderIdAndNewStatusOrderByIdDesc(orderId, currentStatus.name())
                .map(OrderStatusHistoryJpaEntity::getPreviousStatus)
                .filter(previous -> previous != null && !previous.isBlank())
                .map(OrderStatus::fromString);
    }

    @Override
    public List<OrderStatusChange> findHistory(Long orderId) {
        return repository.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(OrderStatusHistoryPersistenceAdapter::toDomain)
                .toList();
    }

    private static OrderStatusChange toDomain(OrderStatusHistoryJpaEntity entity) {
        return new OrderStatusChange(entity.getId(),
                entity.getOrderId(),
                entity.getPreviousStatus(),
                entity.getNewStatus(),
                entity.getChangedBy(),
                entity.getReason(),
                entity.getChangedAt());
    }
}
