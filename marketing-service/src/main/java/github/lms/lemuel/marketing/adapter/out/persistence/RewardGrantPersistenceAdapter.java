package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.RewardStatus;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 보상 지급 요청 어댑터. */
@Component
class RewardGrantPersistenceAdapter implements RewardGrantPort {

    private final RewardGrantJpaRepository repository;

    RewardGrantPersistenceAdapter(RewardGrantJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<RewardGrant> findById(UUID rewardId) {
        return repository.findById(rewardId).map(RewardGrantJpaEntity::toDomain);
    }

    @Override
    public Optional<RewardGrant> findByReference(RewardSource source, UUID referenceId) {
        return repository.findBySourceAndReferenceId(source, referenceId).map(RewardGrantJpaEntity::toDomain);
    }

    @Override
    public List<RewardGrant> findDue(LocalDate on, int limit) {
        return repository
                .findByStatusAndScheduledOnLessThanEqualOrderByScheduledOnAsc(RewardStatus.PENDING, on,
                        Limit.of(limit))
                .stream().map(RewardGrantJpaEntity::toDomain).toList();
    }

    @Override
    public List<RewardGrant> findByMember(String memberRef, int limit) {
        return repository.findByMemberRefOrderByCreatedAtDesc(memberRef, Limit.of(limit))
                .stream().map(RewardGrantJpaEntity::toDomain).toList();
    }

    @Override
    public RewardGrant save(RewardGrant grant) {
        RewardGrantJpaEntity entity = repository.findById(grant.id())
                .map(found -> {
                    found.sync(grant);
                    return found;
                })
                .orElseGet(() -> RewardGrantJpaEntity.fromDomain(grant));
        return repository.save(entity).toDomain();
    }
}
