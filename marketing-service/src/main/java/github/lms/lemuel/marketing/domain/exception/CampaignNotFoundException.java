package github.lms.lemuel.marketing.domain.exception;

import java.util.UUID;

/** 그런 캠페인이 없다. */
public class CampaignNotFoundException extends RuntimeException {
    public CampaignNotFoundException(UUID campaignId) {
        super("캠페인을 찾을 수 없습니다: " + campaignId);
    }
}
