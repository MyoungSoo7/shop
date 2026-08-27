package github.lms.lemuel.marketing.adapter.in.scheduler;

import github.lms.lemuel.marketing.application.port.in.SettleScheduledRewardsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 일괄 지급 보상을 지급일에 요청으로 올린다.
 *
 * <p>럭키박스는 즉시 지급과 일괄 지급 두 가지가 있다. 일괄 지급은 당첨은 그날 하고 포인트는
 * 지정한 날짜에 나간다 — 레거시에서는 그 지급을 운영자가 손으로 SQL 을 돌려서 했고, 잊으면
 * 당첨자는 "당첨" 화면만 보고 포인트를 못 받았다. 그 수기 절차를 대신한다.
 *
 * <p>기준 날짜는 <b>KST</b> 다. 서버가 UTC 로 뜨는 환경에서 {@code LocalDate.now()} 를 쓰면
 * 한국 시간 오전 9시 이전에 도는 잡이 어제 날짜를 보게 되고, 지급이 하루씩 밀린다.
 *
 * <p>여러 인스턴스가 동시에 떠도 중복 지급은 나지 않는다 — 실제 지급은 order-service 가
 * {@code (accountId, referenceType, referenceId)} 로 멱등 처리하고, 우리 쪽도 상태를 PENDING 인
 * 행만 집어 올리므로 두 번째 인스턴스는 빈손이 된다. 다만 무의미한 중복 실행이므로 인스턴스를
 * 늘릴 때는 잡 리더 선출을 붙이는 편이 낫다.
 */
@Component
public class RewardSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(RewardSettlementScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SettleScheduledRewardsUseCase settleUseCase;

    public RewardSettlementScheduler(SettleScheduledRewardsUseCase settleUseCase) {
        this.settleUseCase = settleUseCase;
    }

    @Scheduled(cron = "${app.marketing.settlement.cron}", zone = "Asia/Seoul")
    public void settleDueRewards() {
        LocalDate today = LocalDate.now(KST);
        int settled = settleUseCase.settle(today);
        if (settled > 0) {
            log.info("일괄 지급 보상 {}건을 요청으로 올렸다 (기준일 {})", settled, today);
        }
    }
}
