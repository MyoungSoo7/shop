package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.dto.DrawResultView;
import github.lms.lemuel.marketing.application.port.dto.LuckyboxBoardView;
import github.lms.lemuel.marketing.application.port.dto.LuckyboxPrizeView;
import github.lms.lemuel.marketing.application.port.in.DrawLuckyboxUseCase;
import github.lms.lemuel.marketing.application.port.in.ViewLuckyboxUseCase;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LuckyboxDrawPort;
import github.lms.lemuel.marketing.application.port.out.LuckyboxPrizePort;
import github.lms.lemuel.marketing.application.port.out.RollSource;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxDraw;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.PrizeDraw;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import github.lms.lemuel.marketing.domain.exception.NoPrizeAvailableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 럭키박스 — 조회와 추첨.
 *
 * <p>추첨 한 번이 하는 일은 네 단계다. 참여 자격을 확인하고, 후보에서 하나를 뽑고, 그 경품의
 * <b>수량을 예약</b>하고, 참여 기록을 남긴다. 레거시에는 세 번째 단계가 없었다 —
 * {@code // 아이템 수량 확인} 주석 아래가 비어 있어서 재고가 0 인 경품도 계속 당첨됐고,
 * "선착순 100명" 이 몇 명에게 나갔는지 확인할 방법도 없었다.
 *
 * <p>예약에 실패하면(그 사이 누가 마지막 하나를 가져갔으면) 그 경품을 후보에서 빼고 다시 뽑는다.
 * 실패를 그대로 사용자에게 돌려주면, 재고가 빠듯한 인기 경품이 있을 때 추첨 자체가 실패하는
 * 것처럼 보인다.
 */
@Service
public class LuckyboxService implements ViewLuckyboxUseCase, DrawLuckyboxUseCase {

    /** 예약 경합으로 다시 뽑는 횟수의 상한. 무한 재시도는 경품이 전부 소진된 순간 응답을 붙잡는다. */
    private static final int MAX_RESERVE_ATTEMPTS = 5;

    private final LoadLuckyboxCampaignPort loadCampaignPort;
    private final LuckyboxPrizePort prizePort;
    private final LuckyboxDrawPort drawPort;
    private final RollSource rollSource;
    private final RewardIssuer rewardIssuer;

    public LuckyboxService(LoadLuckyboxCampaignPort loadCampaignPort,
                           LuckyboxPrizePort prizePort,
                           LuckyboxDrawPort drawPort,
                           RollSource rollSource,
                           RewardIssuer rewardIssuer) {
        this.loadCampaignPort = loadCampaignPort;
        this.prizePort = prizePort;
        this.drawPort = drawPort;
        this.rollSource = rollSource;
        this.rewardIssuer = rewardIssuer;
    }

    @Override
    @Transactional(readOnly = true)
    public LuckyboxBoardView board(UUID campaignId, String memberRef, LocalDate on) {
        LuckyboxCampaign campaign = resolve(campaignId, on);

        List<LuckyboxPrizeView> prizes = prizePort.findByCampaign(campaign.id()).stream()
                .filter(LuckyboxPrize::active)
                .sorted(Comparator.comparingInt(LuckyboxPrize::displayOrder))
                // 확률과 잔여 수량은 공개하지 않는다 — 자세한 건 LuckyboxPrizeView 주석에 있다.
                .map(p -> new LuckyboxPrizeView(p.id(), p.prizeType().name(), p.rewardPoints(),
                        p.textReward(), p.displayOrder()))
                .toList();

        boolean alreadyDrawn = drawPort
                .findBySlot(campaign.id(), memberRef, campaign.entrySlot(on))
                .isPresent();

        List<DrawResultView> myDraws = drawPort.findByMember(campaign.id(), memberRef).stream()
                .map(d -> toView(d, campaign.scheduledRewardDate()))
                .toList();

        boolean drawableNow = campaign.status().isPubliclyVisible()
                && !on.isBefore(campaign.startsOn())
                && !on.isAfter(campaign.endsOn())
                && !alreadyDrawn;

        return new LuckyboxBoardView(
                campaign.id(),
                campaign.name(),
                campaign.startsOn(),
                campaign.endsOn(),
                campaign.entryCondition().name(),
                campaign.benefitType().name(),
                campaign.benefitOn(),
                campaign.note(),
                drawableNow,
                alreadyDrawn,
                campaign.banner().pcImageUrl(),
                campaign.banner().mobileImageUrl(),
                prizes,
                myDraws);
    }

    @Override
    @Transactional
    public DrawResultView draw(UUID campaignId, String memberRef, LocalDate on) {
        LuckyboxCampaign campaign = resolve(campaignId, on);
        campaign.assertDrawAllowed(on);

        String slot = campaign.entrySlot(on);
        if (drawPort.findBySlot(campaign.id(), memberRef, slot).isPresent()) {
            throw new AlreadyParticipatedException("이미 참여하셨습니다: " + campaign.name());
        }

        LuckyboxPrize prize = drawAndReserve(campaign.id(), on);

        // 유니크 (campaign_id, member_ref, entry_slot) — 동시 요청 두 건 중 하나는 여기서 튕긴다.
        LuckyboxDraw saved = drawPort.save(LuckyboxDraw.of(UUID.randomUUID(), campaign, memberRef, prize, on));

        LocalDate scheduledOn = campaign.scheduledRewardDate();
        RewardGrant grant = null;
        if (saved.grantsPoints()) {
            grant = rewardIssuer.issue(
                    RewardSource.LUCKYBOX,
                    saved.id(),
                    campaign.id(),
                    campaign.name(),
                    memberRef,
                    saved.rewardPoints(),
                    campaign.rewardExpiresOn(),
                    campaign.name() + " [럭키박스 당첨]",
                    scheduledOn);
        }
        return new DrawResultView(
                saved.id(),
                saved.prizeType().name(),
                saved.rewardPoints(),
                saved.textReward(),
                saved.drawnOn(),
                scheduledOn,
                grant != null);
    }

    /**
     * 하나 뽑고 수량을 예약한다. 예약에 실패한 경품은 후보에서 빼고 다시 뽑는다.
     *
     * <p>예약이 곧 판정이다. 뽑기 전에 재고를 조회해서 거르면 조회와 차감 사이에 창이 생기고,
     * 마지막 한 개가 두 사람에게 나간다.
     */
    private LuckyboxPrize drawAndReserve(UUID campaignId, LocalDate on) {
        List<LuckyboxPrize> candidates = new ArrayList<>(prizePort.findByCampaign(campaignId));
        for (int attempt = 0; attempt < MAX_RESERVE_ATTEMPTS; attempt++) {
            LuckyboxPrize picked = PrizeDraw.select(candidates, rollSource.nextRoll());
            if (prizePort.tryReserve(picked.id(), on)) {
                return picked;
            }
            candidates.removeIf(p -> p.id().equals(picked.id()));
            if (candidates.stream().noneMatch(LuckyboxPrize::isDrawable)) {
                break;
            }
        }
        throw new NoPrizeAvailableException("남은 경품이 없습니다");
    }

    private DrawResultView toView(LuckyboxDraw draw, LocalDate scheduledOn) {
        return new DrawResultView(draw.id(), draw.prizeType().name(), draw.rewardPoints(), draw.textReward(),
                draw.drawnOn(), scheduledOn, draw.grantsPoints());
    }

    /** 캠페인을 정한다 — {@link AttendanceService#board} 와 같은 이유로 정렬을 못 박는다. */
    private LuckyboxCampaign resolve(UUID campaignId, LocalDate on) {
        if (campaignId != null) {
            return loadCampaignPort.findById(campaignId)
                    .orElseThrow(() -> new CampaignNotFoundException(campaignId));
        }
        return loadCampaignPort.findRunningOn(on).stream()
                .min(Comparator.comparing(LuckyboxCampaign::startsOn).thenComparing(LuckyboxCampaign::name))
                .orElseThrow(() -> new CampaignNotOpenException("진행 중인 럭키박스 이벤트가 없습니다"));
    }
}
