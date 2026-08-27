package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.dto.DrawResultView;
import github.lms.lemuel.marketing.application.port.dto.LuckyboxBoardView;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LuckyboxDrawPort;
import github.lms.lemuel.marketing.application.port.out.LuckyboxPrizePort;
import github.lms.lemuel.marketing.application.port.out.RollSource;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxDraw;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.PrizeType;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import github.lms.lemuel.marketing.domain.exception.NoPrizeAvailableException;
import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 럭키박스 유스케이스.
 *
 * <p>레거시에 없던 세 번째 단계 — <b>수량 예약</b> — 이 실제로 판정 역할을 하는지 본다.
 * 예약에 실패한 경품은 후보에서 빠지고 다시 뽑혀야 하며, 예약이 끝내 안 되면 그때서야 실패다.
 */
class LuckyboxServiceTest {

    private static final String MEMBER = MarketingFixtures.MEMBER;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);
    private static final String SLOT = "2026-08-12";

    private LoadLuckyboxCampaignPort loadPort;
    private LuckyboxPrizePort prizePort;
    private LuckyboxDrawPort drawPort;
    private RollSource rollSource;
    private RewardIssuer rewardIssuer;
    private LuckyboxService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadLuckyboxCampaignPort.class);
        prizePort = mock(LuckyboxPrizePort.class);
        drawPort = mock(LuckyboxDrawPort.class);
        rollSource = mock(RollSource.class);
        rewardIssuer = mock(RewardIssuer.class);
        service = new LuckyboxService(loadPort, prizePort, drawPort, rollSource, rewardIssuer);
        when(rollSource.nextRoll()).thenReturn(0.5d);
    }

    // ------------------------------------------------------------ 캠페인 선택

    @Test
    void 없는_캠페인_id_는_거절한다() {
        UUID unknown = UUID.randomUUID();
        when(loadPort.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(CampaignNotFoundException.class, () -> service.board(unknown, MEMBER, TODAY));
    }

    @Test
    void 캠페인_id_가_없으면_먼저_시작한_캠페인을_고른다() {
        LuckyboxCampaign later = MarketingFixtures.luckybox(CampaignStatus.RUNNING, BenefitType.IMMEDIATE, null,
                EntryCondition.PER_DAY, LocalDate.of(2026, 8, 5), MarketingFixtures.END);
        LuckyboxCampaign earlier = MarketingFixtures.runningLuckybox();
        when(loadPort.findRunningOn(TODAY)).thenReturn(List.of(later, earlier));
        when(prizePort.findByCampaign(any())).thenReturn(List.of());
        when(drawPort.findBySlot(any(), any(), any())).thenReturn(Optional.empty());
        when(drawPort.findByMember(any(), any())).thenReturn(List.of());

        assertEquals(earlier.id(), service.board(null, MEMBER, TODAY).campaignId());
    }

    @Test
    void 진행_중인_이벤트가_없으면_거절한다() {
        when(loadPort.findRunningOn(TODAY)).thenReturn(List.of());

        assertThrows(CampaignNotOpenException.class, () -> service.board(null, MEMBER, TODAY));
    }

    // ------------------------------------------------------------ 조회

    @Test
    void 조회는_활성_경품만_노출_순서대로_보여_준다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize second = MarketingFixtures.pointPrize(campaign.id(), "500", 0.3d, 2);
        LuckyboxPrize first = MarketingFixtures.textPrize(campaign.id(), "스티커", 0.7d, 1);
        LuckyboxPrize inactive = new LuckyboxPrize(UUID.randomUUID(), campaign.id(), PrizeType.TEXT,
                null, "지난 경품", null, null, BigDecimal.valueOf(0.5d), 0, false, 0, 0L);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(second, first, inactive));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.empty());
        when(drawPort.findByMember(campaign.id(), MEMBER)).thenReturn(List.of());

        LuckyboxBoardView view = service.board(campaign.id(), MEMBER, TODAY);

        assertEquals(2, view.prizes().size());
        assertEquals(first.id(), view.prizes().get(0).id());
        assertEquals(second.id(), view.prizes().get(1).id());
        assertTrue(view.drawableNow());
        assertFalse(view.alreadyDrawnInSlot());
        assertEquals("PER_DAY", view.entryCondition());
        assertEquals("IMMEDIATE", view.benefitType());
        assertEquals("1일 1회", view.note());
        assertEquals("pc.png", view.pcImageUrl());
        assertEquals("mo.png", view.mobileImageUrl());
        assertNull(view.benefitOn());
    }

    @Test
    void 이미_참여했으면_다시_뽑을_수_없다고_표시한다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize prize = MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 1);
        LuckyboxDraw mine = LuckyboxDraw.of(UUID.randomUUID(), campaign, MEMBER, prize, TODAY);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(prize));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.of(mine));
        when(drawPort.findByMember(campaign.id(), MEMBER)).thenReturn(List.of(mine));

        LuckyboxBoardView view = service.board(campaign.id(), MEMBER, TODAY);

        assertTrue(view.alreadyDrawnInSlot());
        assertFalse(view.drawableNow());
        assertEquals(1, view.myDraws().size());
        assertEquals(mine.id(), view.myDraws().get(0).drawId());
        assertTrue(view.myDraws().get(0).rewardPending());
    }

    @Test
    void 기간_밖이면_뽑기_버튼을_닫는다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of());
        when(drawPort.findBySlot(any(), any(), any())).thenReturn(Optional.empty());
        when(drawPort.findByMember(any(), any())).thenReturn(List.of());

        assertFalse(service.board(campaign.id(), MEMBER, MarketingFixtures.END.plusDays(1)).drawableNow());
    }

    // ------------------------------------------------------------ 추첨

    @Test
    void 즉시_지급_캠페인은_당첨과_동시에_보상을_요청한다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize prize = MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 1);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.empty());
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(prize));
        when(prizePort.tryReserve(prize.id(), TODAY)).thenReturn(true);
        when(drawPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rewardIssuer.issue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(RewardGrant.requestNow(UUID.randomUUID(), RewardSource.LUCKYBOX, UUID.randomUUID(),
                        campaign.id(), MEMBER, new BigDecimal("500"), null, "메모"));

        DrawResultView result = service.draw(campaign.id(), MEMBER, TODAY);

        assertEquals("POINT", result.prizeType());
        assertEquals(new BigDecimal("500"), result.rewardPoints());
        assertEquals(TODAY, result.drawnOn());
        assertNull(result.scheduledOn());
        assertTrue(result.rewardPending());
        verify(rewardIssuer).issue(eq(RewardSource.LUCKYBOX), any(), eq(campaign.id()), eq(campaign.name()),
                eq(MEMBER), eq(new BigDecimal("500")), eq(campaign.rewardExpiresOn()),
                eq("8월 럭키박스 [럭키박스 당첨]"), isNull());
    }

    @Test
    void 일괄_지급_캠페인은_지급일을_실어_보낸다() {
        LocalDate benefitOn = MarketingFixtures.END.plusDays(3);
        LuckyboxCampaign campaign = MarketingFixtures.luckybox(CampaignStatus.RUNNING, BenefitType.BATCH,
                benefitOn, EntryCondition.PER_PERIOD, MarketingFixtures.START, MarketingFixtures.END);
        LuckyboxPrize prize = MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 1);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, "ALL")).thenReturn(Optional.empty());
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(prize));
        when(prizePort.tryReserve(prize.id(), TODAY)).thenReturn(true);
        when(drawPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DrawResultView result = service.draw(campaign.id(), MEMBER, TODAY);

        assertEquals(benefitOn, result.scheduledOn());
        assertFalse(result.rewardPending());   // issue 가 null 을 돌려준 경우
        verify(rewardIssuer).issue(eq(RewardSource.LUCKYBOX), any(), any(), any(), any(), any(), any(),
                any(), eq(benefitOn));
    }

    @Test
    void 텍스트_경품은_보상을_요청하지_않는다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize prize = MarketingFixtures.textPrize(campaign.id(), "스티커", 1.0d, 1);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.empty());
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(prize));
        when(prizePort.tryReserve(prize.id(), TODAY)).thenReturn(true);
        when(drawPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DrawResultView result = service.draw(campaign.id(), MEMBER, TODAY);

        assertEquals("TEXT", result.prizeType());
        assertEquals("스티커", result.textReward());
        assertFalse(result.rewardPending());
        verify(rewardIssuer, never()).issue(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 같은_슬롯에_두_번째_참여는_거절한다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize prize = MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 1);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT))
                .thenReturn(Optional.of(LuckyboxDraw.of(UUID.randomUUID(), campaign, MEMBER, prize, TODAY)));

        assertThrows(AlreadyParticipatedException.class, () -> service.draw(campaign.id(), MEMBER, TODAY));
        verify(prizePort, never()).tryReserve(any(), any());
    }

    @Test
    void 진행_중이_아니면_추첨하지_않는다() {
        LuckyboxCampaign draft = MarketingFixtures.luckybox(CampaignStatus.DRAFT, BenefitType.IMMEDIATE, null,
                EntryCondition.PER_DAY, MarketingFixtures.START, MarketingFixtures.END);
        when(loadPort.findById(draft.id())).thenReturn(Optional.of(draft));

        assertThrows(CampaignNotOpenException.class, () -> service.draw(draft.id(), MEMBER, TODAY));
        verify(drawPort, never()).save(any());
    }

    /** 예약에 실패한 경품은 후보에서 빠지고 다시 뽑힌다 — 추첨 자체가 실패한 것처럼 보이면 안 된다. */
    @Test
    void 예약에_실패하면_그_경품을_빼고_다시_뽑는다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize first = MarketingFixtures.pointPrize(campaign.id(), "100", 1.0d, 1);
        LuckyboxPrize second = MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 2);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.empty());
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(first, second));
        // roll 0.5 · 가중치 1:1 → 첫 시도는 두 번째 경품. 그게 소진됐다.
        when(prizePort.tryReserve(second.id(), TODAY)).thenReturn(false);
        when(prizePort.tryReserve(first.id(), TODAY)).thenReturn(true);
        when(drawPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DrawResultView result = service.draw(campaign.id(), MEMBER, TODAY);

        assertEquals(new BigDecimal("100"), result.rewardPoints());
        verify(prizePort).tryReserve(second.id(), TODAY);
        verify(prizePort).tryReserve(first.id(), TODAY);
    }

    @Test
    void 모든_경품이_소진되면_실패로_알린다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LuckyboxPrize only = MarketingFixtures.pointPrize(campaign.id(), "100", 1.0d, 1);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.empty());
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(only));
        when(prizePort.tryReserve(only.id(), TODAY)).thenReturn(false);

        assertThrows(NoPrizeAvailableException.class, () -> service.draw(campaign.id(), MEMBER, TODAY));
        verify(prizePort, times(1)).tryReserve(only.id(), TODAY);
        verify(drawPort, never()).save(any());
    }

    @Test
    void 경품이_아예_없으면_추첨하지_못한다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(drawPort.findBySlot(campaign.id(), MEMBER, SLOT)).thenReturn(Optional.empty());
        when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of());

        assertThrows(NoPrizeAvailableException.class, () -> service.draw(campaign.id(), MEMBER, TODAY));
    }
}
