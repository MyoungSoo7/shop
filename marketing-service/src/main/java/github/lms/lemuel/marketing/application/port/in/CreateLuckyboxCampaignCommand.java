package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.EntryCondition;

import java.time.LocalDate;

/**
 * 럭키박스 캠페인 등록 명령.
 *
 * <p>{@code entryCondition} 은 <b>참여 빈도</b>(하루 1회/기간 1회)이지 참여 <b>대상</b>이 아니다.
 * 대상 조건(가입일·주문금액·배송상태)은 이 명령에 없다 — 이유는
 * {@code docs/plan/marketing-legacy-gap.md} §2 ④ 에 있다.
 */
public record CreateLuckyboxCampaignCommand(
        String tenantRef,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        BenefitType benefitType,
        LocalDate benefitOn,
        EntryCondition entryCondition,
        LocalDate rewardExpiresOn,
        String note,
        String pcImageUrl,
        String mobileImageUrl,
        String actor
) {
}
