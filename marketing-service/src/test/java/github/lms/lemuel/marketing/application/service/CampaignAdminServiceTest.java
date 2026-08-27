package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.in.CreateAttendanceCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxPrizeCommand;
import github.lms.lemuel.marketing.application.port.in.UpdateAttendanceCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.UpdateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LuckyboxPrizePort;
import github.lms.lemuel.marketing.application.port.out.SaveAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.SaveLuckyboxCampaignPort;
import github.lms.lemuel.marketing.domain.AmountBasis;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.PrizeType;
import github.lms.lemuel.marketing.domain.ShippingStatusRequirement;
import github.lms.lemuel.marketing.domain.StreakRule;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 운영자 쪽 두 서비스.
 *
 * <p>레거시와 달라진 두 지점을 여기서 못 박는다 — 등록은 {@code DRAFT} 로 끝나고(등록하는 순간
 * 노출되지 않는다), 뽑을 경품이 없는 럭키박스는 열리지 않는다.
 */
class CampaignAdminServiceTest {

    private static final String ACTOR = "admin";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Nested
    class 출석_캠페인 {

        private LoadAttendanceCampaignPort loadPort;
        private SaveAttendanceCampaignPort savePort;
        private AttendanceCampaignAdminService service;

        @BeforeEach
        void setUp() {
            loadPort = mock(LoadAttendanceCampaignPort.class);
            savePort = mock(SaveAttendanceCampaignPort.class);
            service = new AttendanceCampaignAdminService(loadPort, savePort);
            when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        private CreateAttendanceCampaignCommand createCommand() {
            return new CreateAttendanceCampaignCommand("tenant-1", "9월 출석", PeriodType.DAILY, START, END,
                    StreakRule.CONSECUTIVE, 3, DayTypeRule.EVERY_DAY, new BigDecimal("10"), new BigDecimal("100"),
                    null, null, "pc.png", "mo.png", "곧 시작", "진행 중", "달성 축하", "종료", ACTOR);
        }

        /** 등록하는 순간 노출되던 레거시 동작을 일부러 바꿨다 — 개시는 별도 호출이다. */
        @Test
        void 등록은_DRAFT_로_끝난다() {
            UUID id = service.create(createCommand());

            ArgumentCaptor<AttendanceCampaign> saved = ArgumentCaptor.forClass(AttendanceCampaign.class);
            verify(savePort).save(saved.capture());
            assertNotNull(id);
            assertEquals(id, saved.getValue().id());
            assertEquals(CampaignStatus.DRAFT, saved.getValue().status());
            assertEquals("9월 출석", saved.getValue().name());
            assertEquals("pc.png", saved.getValue().banner().pcImageUrl());
            assertEquals(StreakRule.CONSECUTIVE, saved.getValue().streakRule());
        }

        @Test
        void 수정은_기간과_보상과_문구만_바꾼다() {
            AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));

            service.update(new UpdateAttendanceCampaignCommand(campaign.id(), "9월 출석", START, END,
                    new BigDecimal("20"), new BigDecimal("200"), "pc2.png", "mo2.png",
                    "곧", "중", "축", "끝", "admin2"));

            assertEquals("9월 출석", campaign.name());
            assertEquals(START, campaign.startsOn());
            assertEquals(new BigDecimal("20"), campaign.dailyRewardPoints());
            assertEquals("pc2.png", campaign.banner().pcImageUrl());
            assertEquals(StreakRule.CONSECUTIVE, campaign.streakRule());   // 집계 규칙은 그대로
            verify(savePort).save(campaign);
        }

        @Test
        void 개시와_종료는_상태를_바꾸고_저장한다() {
            AttendanceCampaign campaign = MarketingFixtures.attendance(CampaignStatus.DRAFT, PeriodType.DAILY,
                    StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.ZERO, BigDecimal.ZERO,
                    MarketingFixtures.START, MarketingFixtures.END);
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));

            service.open(campaign.id(), ACTOR);
            assertEquals(CampaignStatus.RUNNING, campaign.status());

            service.close(campaign.id(), ACTOR);
            assertEquals(CampaignStatus.CLOSED, campaign.status());
        }

        @Test
        void 없는_캠페인은_찾을_수_없다고_알린다() {
            UUID gone = UUID.randomUUID();
            when(loadPort.findById(gone)).thenReturn(Optional.empty());

            assertThrows(CampaignNotFoundException.class, () -> service.get(gone));
            assertThrows(CampaignNotFoundException.class, () -> service.open(gone, ACTOR));
            verify(savePort, never()).save(any());
        }

        @Test
        void 운영_목록은_상태와_무관하게_전부_보여_준다() {
            AttendanceCampaign draft = MarketingFixtures.attendance(CampaignStatus.DRAFT, PeriodType.DAILY,
                    StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.ZERO, BigDecimal.ZERO,
                    MarketingFixtures.START, MarketingFixtures.END);
            when(loadPort.findAllForAdmin()).thenReturn(List.of(draft));

            assertEquals(List.of(draft), service.list());
        }
    }

    @Nested
    class 럭키박스_캠페인 {

        private LoadLuckyboxCampaignPort loadPort;
        private SaveLuckyboxCampaignPort savePort;
        private LuckyboxPrizePort prizePort;
        private LuckyboxCampaignAdminService service;

        @BeforeEach
        void setUp() {
            loadPort = mock(LoadLuckyboxCampaignPort.class);
            savePort = mock(SaveLuckyboxCampaignPort.class);
            prizePort = mock(LuckyboxPrizePort.class);
            service = new LuckyboxCampaignAdminService(loadPort, savePort, prizePort);
        }

        private CreateLuckyboxCampaignCommand createCommand() {
            return new CreateLuckyboxCampaignCommand("tenant-1", "9월 럭키박스", START, END, BenefitType.IMMEDIATE,
                    null, EntryCondition.PER_DAY, null, LocalDate.of(2026, 12, 31), AmountBasis.ACTUAL_PAID,
                    new BigDecimal("10000"), ShippingStatusRequirement.DELIVERED, "1일 1회",
                    "pc.png", "mo.png", ACTOR);
        }

        @Test
        void 등록은_DRAFT_로_끝난다() {
            when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UUID id = service.create(createCommand());

            ArgumentCaptor<LuckyboxCampaign> saved = ArgumentCaptor.forClass(LuckyboxCampaign.class);
            verify(savePort).save(saved.capture());
            assertEquals(id, saved.getValue().id());
            assertEquals(CampaignStatus.DRAFT, saved.getValue().status());
            assertEquals(EntryCondition.PER_DAY, saved.getValue().entryCondition());
            assertEquals(new BigDecimal("10000"), saved.getValue().minOrderAmount());
        }

        @Test
        void 수정은_참여_조건을_건드리지_않는다() {
            LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
            when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.update(new UpdateLuckyboxCampaignCommand(campaign.id(), "9월 럭키박스", START, END, null,
                    LocalDate.of(2027, 1, 31), "메모 수정", "pc2.png", "mo2.png", "admin2"));

            assertEquals("9월 럭키박스", campaign.name());
            assertEquals(EntryCondition.PER_DAY, campaign.entryCondition());
            assertEquals(BenefitType.IMMEDIATE, campaign.benefitType());
            assertEquals(LocalDate.of(2027, 1, 31), campaign.rewardExpiresOn());
            verify(savePort).save(campaign);
        }

        /** 레거시는 경품 없이도 열렸다 — 참여하면 아무 일도 없이 참여 횟수만 사라졌다. */
        @Test
        void 뽑을_경품이_없으면_열리지_않는다() {
            LuckyboxCampaign campaign = MarketingFixtures.luckybox(CampaignStatus.DRAFT, BenefitType.IMMEDIATE,
                    null, EntryCondition.PER_DAY, MarketingFixtures.START, MarketingFixtures.END);
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
            when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of());

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.open(campaign.id(), ACTOR));

            assertTrue(e.getMessage().contains("8월 럭키박스"));
            assertEquals(CampaignStatus.DRAFT, campaign.status());
            verify(savePort, never()).save(any());
        }

        /** 꺼진 경품만 남은 것도 "없는" 것과 같다. */
        @Test
        void 비활성_경품만_있어도_열리지_않는다() {
            LuckyboxCampaign campaign = MarketingFixtures.luckybox(CampaignStatus.DRAFT, BenefitType.IMMEDIATE,
                    null, EntryCondition.PER_DAY, MarketingFixtures.START, MarketingFixtures.END);
            LuckyboxPrize prize = MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 1);
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
            when(prizePort.findByCampaign(campaign.id())).thenReturn(List.of(deactivate(prize)));

            assertThrows(IllegalStateException.class, () -> service.open(campaign.id(), ACTOR));
        }

        @Test
        void 경품이_있으면_열고_닫을_수_있다() {
            LuckyboxCampaign campaign = MarketingFixtures.luckybox(CampaignStatus.DRAFT, BenefitType.IMMEDIATE,
                    null, EntryCondition.PER_DAY, MarketingFixtures.START, MarketingFixtures.END);
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
            when(prizePort.findByCampaign(campaign.id()))
                    .thenReturn(List.of(MarketingFixtures.pointPrize(campaign.id(), "500", 1.0d, 1)));
            when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.open(campaign.id(), ACTOR);
            assertEquals(CampaignStatus.RUNNING, campaign.status());

            service.close(campaign.id(), ACTOR);
            assertEquals(CampaignStatus.CLOSED, campaign.status());
        }

        @Test
        void 없는_캠페인은_찾을_수_없다고_알린다() {
            UUID gone = UUID.randomUUID();
            when(loadPort.findById(gone)).thenReturn(Optional.empty());

            assertThrows(CampaignNotFoundException.class, () -> service.get(gone));
            assertThrows(CampaignNotFoundException.class, () -> service.close(gone, ACTOR));
        }

        @Test
        void 운영_목록은_상태와_무관하게_전부_보여_준다() {
            LuckyboxCampaign draft = MarketingFixtures.luckybox(CampaignStatus.DRAFT, BenefitType.IMMEDIATE,
                    null, EntryCondition.PER_DAY, MarketingFixtures.START, MarketingFixtures.END);
            when(loadPort.findAllForAdmin()).thenReturn(List.of(draft));

            assertEquals(List.of(draft), service.list());
        }

        @Test
        void 경품은_소진량_0_활성_상태로_달린다() {
            LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
            when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
            when(prizePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UUID id = service.addPrize(new CreateLuckyboxPrizeCommand(campaign.id(), PrizeType.POINT,
                    new BigDecimal("500"), null, 100, 10, new BigDecimal("0.5"), 2, ACTOR));

            ArgumentCaptor<LuckyboxPrize> saved = ArgumentCaptor.forClass(LuckyboxPrize.class);
            verify(prizePort).save(saved.capture());
            assertEquals(id, saved.getValue().id());
            assertEquals(0, saved.getValue().issuedCount());
            assertEquals(0L, saved.getValue().version());
            assertTrue(saved.getValue().active());
            assertEquals(2, saved.getValue().displayOrder());
            assertEquals(100, saved.getValue().totalQuota());
        }

        @Test
        void 없는_캠페인에는_경품을_달_수_없다() {
            UUID gone = UUID.randomUUID();
            when(loadPort.findById(gone)).thenReturn(Optional.empty());

            assertThrows(CampaignNotFoundException.class, () -> service.addPrize(
                    new CreateLuckyboxPrizeCommand(gone, PrizeType.POINT, new BigDecimal("500"), null,
                            null, null, new BigDecimal("0.5"), 1, ACTOR)));
            verify(prizePort, never()).save(any());
        }

        @Test
        void 경품_목록은_노출_순서대로_준다() {
            UUID campaignId = UUID.randomUUID();
            LuckyboxPrize second = MarketingFixtures.pointPrize(campaignId, "500", 0.5d, 2);
            LuckyboxPrize first = MarketingFixtures.textPrize(campaignId, "꽝", 0.5d, 1);
            when(prizePort.findByCampaign(campaignId)).thenReturn(List.of(second, first));

            assertEquals(List.of(first, second), service.prizes(campaignId));
        }

        /** 지우지 않고 끈다 — 이미 당첨된 사람의 기록이 이 행을 참조한다. */
        @Test
        void 경품_비활성화는_행을_남기고_끄기만_한다() {
            LuckyboxPrize prize = MarketingFixtures.pointPrize(UUID.randomUUID(), "500", 0.5d, 1);
            when(prizePort.findById(prize.id())).thenReturn(Optional.of(prize));
            when(prizePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.deactivatePrize(prize.id(), ACTOR);

            ArgumentCaptor<LuckyboxPrize> saved = ArgumentCaptor.forClass(LuckyboxPrize.class);
            verify(prizePort).save(saved.capture());
            assertEquals(prize.id(), saved.getValue().id());
            assertFalse(saved.getValue().active());
            assertFalse(saved.getValue().isDrawable());
            assertEquals(prize.winRate(), saved.getValue().winRate());
            assertEquals(prize.version(), saved.getValue().version());
        }

        @Test
        void 없는_경품은_비활성화할_수_없다() {
            UUID gone = UUID.randomUUID();
            when(prizePort.findById(gone)).thenReturn(Optional.empty());

            assertThrows(NoSuchElementException.class, () -> service.deactivatePrize(gone, ACTOR));
            verify(prizePort, never()).save(any());
        }

        private LuckyboxPrize deactivate(LuckyboxPrize prize) {
            return new LuckyboxPrize(prize.id(), prize.campaignId(), prize.prizeType(), prize.rewardPoints(),
                    prize.textReward(), prize.totalQuota(), prize.dailyQuota(), prize.winRate(),
                    prize.issuedCount(), false, prize.displayOrder(), prize.version());
        }
    }
}
