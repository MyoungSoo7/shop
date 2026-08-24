package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.OrderIdempotencyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OrderIdempotencyPersistenceAdapter implements OrderIdempotencyPort {

    private final SpringDataOrderIdempotencyRepository repository;

    @Override
    public Optional<Long> findOrderId(String idempotencyKey) {
        return repository.findById(idempotencyKey)
                .map(OrderIdempotencyJpaEntity::getOrderId);
    }

    @Override
    public void save(String idempotencyKey, Long orderId) {
        // 네이티브 INSERT — 중복 키면 DataIntegrityViolationException (merge=UPDATE 회피).
        repository.insert(idempotencyKey, orderId);
    }
}
