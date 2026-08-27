package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.domain.AmountBasis;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.ShippingStatusRequirement;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 럭키박스 캠페인 등록 명령. */
public record CreateLuckyboxCampaignCommand(
        String tenantRef,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        BenefitType benefitType,
        LocalDate benefitOn,
        EntryCondition entryCondition,
        LocalDate memberJoinedFrom,
        LocalDate rewardExpiresOn,
        AmountBasis amountBasis,
        BigDecimal minOrderAmount,
        ShippingStatusRequirement shippingStatusRequired,
        String note,
        String pcImageUrl,
        String mobileImageUrl,
        String actor
) {
}
