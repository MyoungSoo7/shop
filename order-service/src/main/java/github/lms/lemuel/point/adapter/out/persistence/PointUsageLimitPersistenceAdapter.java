package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.application.port.out.PointUsageLimitPort;
import github.lms.lemuel.point.domain.PointUsageLimit;
import org.springframework.stereotype.Repository;

@Repository
public class PointUsageLimitPersistenceAdapter implements PointUsageLimitPort {

    private final SpringDataPointUsageLimitPolicyRepository repository;

    public PointUsageLimitPersistenceAdapter(SpringDataPointUsageLimitPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public PointUsageLimit load() {
        return repository.findById(PointUsageLimitPolicyJpaEntity.SINGLETON_ID)
                .map(PointUsageLimitPolicyJpaEntity::toDomain)
                .orElseGet(PointUsageLimit::none);
    }

    @Override
    public PointUsageLimit save(PointUsageLimit limit, String actor) {
        PointUsageLimitPolicyJpaEntity entity = repository
                .findById(PointUsageLimitPolicyJpaEntity.SINGLETON_ID)
                .orElseGet(() -> PointUsageLimitPolicyJpaEntity.singleton(limit, actor));
        entity.apply(limit, actor);
        return repository.save(entity).toDomain();
    }
}
