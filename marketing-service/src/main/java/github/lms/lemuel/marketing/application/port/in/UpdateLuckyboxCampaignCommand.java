package github.lms.lemuel.marketing.application.port.in;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 럭키박스 캠페인 수정 명령.
 *
 * <p>참여 조건과 지급 방식은 못 바꾼다 — 쌓인 참여 기록의 슬롯 키 의미가 소급해 달라져서,
 * 기간 1회로 참여한 사람이 하루 1회로 바뀌는 순간 다시 참여할 수 있게 된다.
 */
public record UpdateLuckyboxCampaignCommand(
        UUID campaignId,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        LocalDate benefitOn,
        LocalDate rewardExpiresOn,
        String note,
        String pcImageUrl,
        String mobileImageUrl,
        String actor
) {
}
