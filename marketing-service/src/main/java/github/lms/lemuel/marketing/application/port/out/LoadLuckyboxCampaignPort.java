package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.LuckyboxCampaign;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 럭키박스 캠페인 적재. */
public interface LoadLuckyboxCampaignPort {

    Optional<LuckyboxCampaign> findById(UUID campaignId);

    List<LuckyboxCampaign> findRunningOn(LocalDate on);

    List<LuckyboxCampaign> findAllForAdmin();
}
