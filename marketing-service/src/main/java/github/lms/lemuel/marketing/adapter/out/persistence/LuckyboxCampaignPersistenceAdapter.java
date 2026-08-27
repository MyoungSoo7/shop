package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.SaveLuckyboxCampaignPort;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 럭키박스 캠페인 적재·저장 어댑터. */
@Component
class LuckyboxCampaignPersistenceAdapter implements LoadLuckyboxCampaignPort, SaveLuckyboxCampaignPort {

    private final LuckyboxCampaignJpaRepository repository;

    LuckyboxCampaignPersistenceAdapter(LuckyboxCampaignJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<LuckyboxCampaign> findById(UUID campaignId) {
        return repository.findById(campaignId).map(LuckyboxCampaignJpaEntity::toDomain);
    }

    @Override
    public List<LuckyboxCampaign> findRunningOn(LocalDate on) {
        return repository
                .findByStatusAndStartsOnLessThanEqualAndEndsOnGreaterThanEqualOrderByStartsOnAscNameAsc(
                        CampaignStatus.RUNNING, on, on)
                .stream().map(LuckyboxCampaignJpaEntity::toDomain).toList();
    }

    @Override
    public List<LuckyboxCampaign> findAllForAdmin() {
        return repository.findAllByOrderByStartsOnDescNameAsc()
                .stream().map(LuckyboxCampaignJpaEntity::toDomain).toList();
    }

    @Override
    public LuckyboxCampaign save(LuckyboxCampaign campaign) {
        LuckyboxCampaignJpaEntity entity = repository.findById(campaign.id())
                .map(found -> {
                    found.sync(campaign);
                    return found;
                })
                .orElseGet(() -> LuckyboxCampaignJpaEntity.fromDomain(campaign));
        return repository.save(entity).toDomain();
    }
}
