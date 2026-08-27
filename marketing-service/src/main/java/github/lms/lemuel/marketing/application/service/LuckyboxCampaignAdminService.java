package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxPrizeCommand;
import github.lms.lemuel.marketing.application.port.in.ManageLuckyboxCampaignUseCase;
import github.lms.lemuel.marketing.application.port.in.UpdateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LuckyboxPrizePort;
import github.lms.lemuel.marketing.application.port.out.SaveLuckyboxCampaignPort;
import github.lms.lemuel.marketing.domain.CampaignBanner;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 럭키박스 캠페인·경품 운영. */
@Service
public class LuckyboxCampaignAdminService implements ManageLuckyboxCampaignUseCase {

    private final LoadLuckyboxCampaignPort loadPort;
    private final SaveLuckyboxCampaignPort savePort;
    private final LuckyboxPrizePort prizePort;

    public LuckyboxCampaignAdminService(LoadLuckyboxCampaignPort loadPort,
                                        SaveLuckyboxCampaignPort savePort,
                                        LuckyboxPrizePort prizePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.prizePort = prizePort;
    }

    @Override
    @Transactional
    public UUID create(CreateLuckyboxCampaignCommand c) {
        LuckyboxCampaign campaign = LuckyboxCampaign.draft(
                UUID.randomUUID(), c.tenantRef(), c.name(), c.startsOn(), c.endsOn(), c.benefitType(),
                c.benefitOn(), c.entryCondition(), c.memberJoinedFrom(), c.rewardExpiresOn(), c.amountBasis(),
                c.minOrderAmount(), c.shippingStatusRequired(), c.note(),
                CampaignBanner.of(c.pcImageUrl(), c.mobileImageUrl()), c.actor());
        return savePort.save(campaign).id();
    }

    @Override
    @Transactional
    public void update(UpdateLuckyboxCampaignCommand c) {
        LuckyboxCampaign campaign = get(c.campaignId());
        campaign.update(c.name(), c.startsOn(), c.endsOn(), c.benefitOn(), c.rewardExpiresOn(), c.note(),
                CampaignBanner.of(c.pcImageUrl(), c.mobileImageUrl()), c.actor());
        savePort.save(campaign);
    }

    @Override
    @Transactional
    public void open(UUID campaignId, String actor) {
        LuckyboxCampaign campaign = get(campaignId);
        // 뽑을 게 없는 이벤트를 여는 것은 사고다 — 레거시는 경품 없이도 열렸고, 참여하면
        // 아무 일도 일어나지 않은 채 참여 횟수만 소진됐다.
        if (prizePort.findByCampaign(campaignId).stream().noneMatch(LuckyboxPrize::isDrawable)) {
            throw new IllegalStateException("추첨 가능한 경품이 없어 이벤트를 열 수 없습니다: " + campaign.name());
        }
        campaign.open(actor);
        savePort.save(campaign);
    }

    @Override
    @Transactional
    public void close(UUID campaignId, String actor) {
        LuckyboxCampaign campaign = get(campaignId);
        campaign.close(actor);
        savePort.save(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LuckyboxCampaign> list() {
        return loadPort.findAllForAdmin();
    }

    @Override
    @Transactional(readOnly = true)
    public LuckyboxCampaign get(UUID campaignId) {
        return loadPort.findById(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
    }

    @Override
    @Transactional
    public UUID addPrize(CreateLuckyboxPrizeCommand c) {
        get(c.campaignId());   // 없는 캠페인에 경품을 매달지 않는다
        LuckyboxPrize prize = new LuckyboxPrize(UUID.randomUUID(), c.campaignId(), c.prizeType(),
                c.rewardPoints(), c.textReward(), c.totalQuota(), c.dailyQuota(), c.winRate(),
                0, true, c.displayOrder(), 0L);
        return prizePort.save(prize).id();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LuckyboxPrize> prizes(UUID campaignId) {
        return prizePort.findByCampaign(campaignId).stream()
                .sorted(Comparator.comparingInt(LuckyboxPrize::displayOrder))
                .toList();
    }

    @Override
    @Transactional
    public void deactivatePrize(UUID prizeId, String actor) {
        LuckyboxPrize prize = prizePort.findById(prizeId)
                .orElseThrow(() -> new NoSuchElementException("경품을 찾을 수 없습니다: " + prizeId));
        // 지우지 않고 끈다. 이미 당첨된 사람의 기록이 이 행을 참조하고 있고, 확률 가중치는
        // 활성 경품 합으로 정규화되므로 끄는 것만으로 추첨에서 빠진다.
        prizePort.save(new LuckyboxPrize(prize.id(), prize.campaignId(), prize.prizeType(), prize.rewardPoints(),
                prize.textReward(), prize.totalQuota(), prize.dailyQuota(), prize.winRate(), prize.issuedCount(),
                false, prize.displayOrder(), prize.version()));
    }
}
