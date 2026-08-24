package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.PaymentIdempotencyPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PaymentIdempotencyPersistenceAdapter implements PaymentIdempotencyPort {

    private final SpringDataPaymentIdempotencyRepository repository;

    public PaymentIdempotencyPersistenceAdapter(SpringDataPaymentIdempotencyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Long> findPaymentId(String idempotencyKey) {
        return repository.findById(idempotencyKey)
                .map(PaymentIdempotencyJpaEntity::getPaymentId);
    }

    @Override
    public void save(String idempotencyKey, Long paymentId) {
        // 네이티브 INSERT — 중복 키면 DataIntegrityViolationException (merge=UPDATE 회피).
        repository.insert(idempotencyKey, paymentId);
    }
}
