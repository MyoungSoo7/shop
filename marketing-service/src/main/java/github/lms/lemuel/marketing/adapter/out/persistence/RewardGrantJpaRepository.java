package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.RewardStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RewardGrantJpaRepository extends JpaRepository<RewardGrantJpaEntity, UUID> {

    Optional<RewardGrantJpaEntity> findBySourceAndReferenceId(RewardSource source, UUID referenceId);

    List<RewardGrantJpaEntity> findByStatusAndScheduledOnLessThanEqualOrderByScheduledOnAsc(
            RewardStatus status, LocalDate on, Limit limit);

    List<RewardGrantJpaEntity> findByMemberRefOrderByCreatedAtDesc(String memberRef, Limit limit);

    long countByStatusAndRequestedAtBefore(RewardStatus status, Instant before);
}
