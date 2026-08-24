package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.LoadSellerShippingPolicyPort;
import github.lms.lemuel.shipping.application.port.out.SaveSellerShippingPolicyPort;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SellerShippingPolicyPersistenceAdapter
        implements LoadSellerShippingPolicyPort, SaveSellerShippingPolicyPort {

    private final SpringDataSellerShippingPolicyRepository repository;

    public SellerShippingPolicyPersistenceAdapter(SpringDataSellerShippingPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<Long, SellerShippingPolicy> loadBySellerIds(Collection<Long> sellerIds) {
        if (sellerIds == null || sellerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SellerShippingPolicy> result = new HashMap<>();
        for (SellerShippingPolicyJpaEntity entity : repository.findBySellerIdIn(sellerIds)) {
            result.put(entity.getSellerId(), toDomain(entity));
        }
        return result;
    }

    @Override
    public Optional<SellerShippingPolicy> loadBySellerId(Long sellerId) {
        return repository.findById(sellerId).map(this::toDomain);
    }

    @Override
    public List<SellerShippingPolicy> loadAll() {
        return repository.findAllByOrderBySellerIdAsc().stream().map(this::toDomain).toList();
    }

    @Override
    public SellerShippingPolicy save(SellerShippingPolicy policy) {
        SellerShippingPolicyJpaEntity entity = repository.findById(policy.getSellerId())
                .orElseGet(() -> new SellerShippingPolicyJpaEntity(
                        policy.getSellerId(), policy.getBaseFee(), policy.getFreeThreshold()));
        entity.applyChange(policy.getBaseFee(), policy.getFreeThreshold());
        return toDomain(repository.save(entity));
    }

    private SellerShippingPolicy toDomain(SellerShippingPolicyJpaEntity entity) {
        return SellerShippingPolicy.rehydrate(
                entity.getSellerId(), entity.getBaseFee(), entity.getFreeThreshold());
    }
}
