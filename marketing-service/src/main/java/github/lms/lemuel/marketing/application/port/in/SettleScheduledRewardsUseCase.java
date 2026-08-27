package github.lms.lemuel.marketing.application.port.in;

import java.time.LocalDate;

/**
 * 지급일이 된 대기 보상을 요청으로 넘긴다 (일괄 지급 캠페인).
 *
 * @return 요청으로 넘긴 건수
 */
public interface SettleScheduledRewardsUseCase {
    int settle(LocalDate on);
}
