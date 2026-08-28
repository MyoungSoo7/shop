package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.in.SettleScheduledRewardsUseCase;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.PublishRewardRequestedPort;
import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.RewardGrant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 지급일이 된 대기 보상을 요청으로 넘긴다 — 일괄 지급(BATCH) 캠페인의 정산.
 *
 * <p>레거시에는 이 개념이 {@code BENEFIT_TYPE=2} 컬럼으로만 있었고, 실제로 지급일에 무언가를
 * 돌리는 배치는 없었다. 운영자가 관리자 화면에서 당첨자 목록을 엑셀로 받아 마일리지를 수동으로
 * 올렸다. 그 수작업이 여기 스케줄러 한 번으로 대체된다.
 *
 * <p>한 번에 {@link #BATCH_LIMIT} 건씩만 처리한다. 캠페인 종료 다음날 수만 건이 한 트랜잭션에
 * 몰리면 outbox 적재가 통째로 길어지고, 그동안 실시간 보상 요청이 같은 테이블에서 대기한다.
 * 남은 건은 다음 주기가 가져간다 — 지급이 몇 분 늦는 것과 서비스가 멈추는 것은 다른 문제다.
 */
@Service
public class RewardSettlementService implements SettleScheduledRewardsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RewardSettlementService.class);
    private static final int BATCH_LIMIT = 500;

    private final RewardGrantPort rewardGrantPort;
    private final PublishRewardRequestedPort publishRewardRequestedPort;
    private final LoadLuckyboxCampaignPort luckyboxCampaignPort;
    private final LoadAttendanceCampaignPort attendanceCampaignPort;

    public RewardSettlementService(RewardGrantPort rewardGrantPort,
                                   PublishRewardRequestedPort publishRewardRequestedPort,
                                   LoadLuckyboxCampaignPort luckyboxCampaignPort,
                                   LoadAttendanceCampaignPort attendanceCampaignPort) {
        this.rewardGrantPort = rewardGrantPort;
        this.publishRewardRequestedPort = publishRewardRequestedPort;
        this.luckyboxCampaignPort = luckyboxCampaignPort;
        this.attendanceCampaignPort = attendanceCampaignPort;
    }

    @Override
    @Transactional
    public int settle(LocalDate on) {
        List<RewardGrant> due = rewardGrantPort.findDue(on, BATCH_LIMIT);
        if (due.isEmpty()) {
            return 0;
        }
        for (RewardGrant grant : due) {
            grant.markRequested();
            rewardGrantPort.save(grant);
            publishRewardRequestedPort.rewardRequested(grant, campaignNameOf(grant));
        }
        log.info("일괄 지급 보상 요청: {}건 (기준일 {})", due.size(), on);
        return due.size();
    }

    /**
     * 이벤트에 실을 캠페인 이름.
     *
     * <p>보상 행에 이름을 복사해 두지 않은 대신 여기서 찾는다. 캠페인이 지워졌거나(있으면 안 되는
     * 일이지만) 못 찾으면 메모를 쓴다 — 이름 하나 때문에 지급이 막히는 게 더 나쁘다.
     */
    private String campaignNameOf(RewardGrant grant) {
        return luckyboxCampaignPort.findById(grant.campaignId())
                .map(LuckyboxCampaign::name)
                .or(() -> attendanceCampaignPort.findById(grant.campaignId()).map(AttendanceCampaign::name))
                .orElseGet(grant::memo);
    }
}
