package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.LuckyboxCampaign;

/** 럭키박스 캠페인 저장. */
public interface SaveLuckyboxCampaignPort {
    LuckyboxCampaign save(LuckyboxCampaign campaign);
}
