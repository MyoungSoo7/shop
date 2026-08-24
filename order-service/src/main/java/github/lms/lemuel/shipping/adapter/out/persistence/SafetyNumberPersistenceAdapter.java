package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.SafetyNumberPort;
import github.lms.lemuel.shipping.domain.SafetyNumber;
import github.lms.lemuel.shipping.domain.SafetyNumberStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SafetyNumberPersistenceAdapter implements SafetyNumberPort {

    private final SpringDataSafetyNumberRepository repository;

    public SafetyNumberPersistenceAdapter(SpringDataSafetyNumberRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SafetyNumber> findAssignedByOrderId(Long orderId) {
        return repository.findByOrderIdAndStatus(orderId, SafetyNumberStatus.ASSIGNED)
                .map(SafetyNumberJpaEntity::toDomain);
    }

    @Override
    public Optional<SafetyNumber> claimAvailable() {
        return repository.lockAvailable(SafetyNumberStatus.AVAILABLE, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(SafetyNumberJpaEntity::toDomain);
    }

    @Override
    public List<SafetyNumber> findExpired(OffsetDateTime now, int limit) {
        return repository.findExpired(SafetyNumberStatus.ASSIGNED, now, PageRequest.of(0, limit))
                .stream()
                .map(SafetyNumberJpaEntity::toDomain)
                .toList();
    }

    @Override
    public SafetyNumber save(SafetyNumber safetyNumber) {
        SafetyNumberJpaEntity entity = safetyNumber.getId() == null
                ? SafetyNumberJpaEntity.from(safetyNumber)
                : repository.findById(safetyNumber.getId())
                        .orElseGet(() -> SafetyNumberJpaEntity.from(safetyNumber));
        entity.apply(safetyNumber);
        return repository.save(entity).toDomain();
    }
}
