package github.lms.lemuel.marketing.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LuckyboxDrawJpaRepository extends JpaRepository<LuckyboxDrawJpaEntity, UUID> {

    Optional<LuckyboxDrawJpaEntity> findByCampaignIdAndMemberRefAndEntrySlot(
            UUID campaignId, String memberRef, String entrySlot);

    List<LuckyboxDrawJpaEntity> findByCampaignIdAndMemberRefOrderByDrawnAtDesc(UUID campaignId, String memberRef);

    List<LuckyboxDrawJpaEntity> findByMemberRefOrderByDrawnAtDesc(String memberRef, Limit limit);
}
