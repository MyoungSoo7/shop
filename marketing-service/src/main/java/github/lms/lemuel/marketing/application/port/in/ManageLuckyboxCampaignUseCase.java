package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;

import java.util.List;
import java.util.UUID;

/** 럭키박스 캠페인·경품 운영. */
public interface ManageLuckyboxCampaignUseCase {

    UUID create(CreateLuckyboxCampaignCommand command);

    void update(UpdateLuckyboxCampaignCommand command);

    void open(UUID campaignId, String actor);

    void close(UUID campaignId, String actor);

    List<LuckyboxCampaign> list();

    LuckyboxCampaign get(UUID campaignId);

    UUID addPrize(CreateLuckyboxPrizeCommand command);

    /** 운영자에게는 확률·잔여 수량까지 보인다. */
    List<LuckyboxPrize> prizes(UUID campaignId);

    void deactivatePrize(UUID prizeId, String actor);
}
