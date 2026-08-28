package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 캠페인 주변의 작은 값 타입들.
 *
 * <p>하나씩 파일을 두면 열 개가 넘고 각각은 열 줄이다. 묶되 {@code @Nested} 로 갈라서
 * 실패했을 때 어느 타입인지는 그대로 보이게 했다.
 */
class MarketingValueTypesTest {

    @Nested
    class 집계_구간 {

        @Test
        void 일간은_캠페인_경계를_그대로_쓴다() {
            LocalDate start = LocalDate.of(2026, 8, 1);
            LocalDate end = LocalDate.of(2026, 8, 31);

            assertEquals(start, PeriodType.DAILY.windowStart(start, LocalDate.of(2026, 8, 20)));
            assertEquals(end, PeriodType.DAILY.windowEnd(end, LocalDate.of(2026, 8, 20)));
        }

        @Test
        void 월간은_그_달로_자르되_캠페인_밖으로_나가지_않는다() {
            LocalDate start = LocalDate.of(2026, 8, 10);
            LocalDate end = LocalDate.of(2026, 9, 20);

            assertEquals(start, PeriodType.MONTHLY.windowStart(start, LocalDate.of(2026, 8, 15)));
            assertEquals(LocalDate.of(2026, 9, 1), PeriodType.MONTHLY.windowStart(start, LocalDate.of(2026, 9, 5)));
            assertEquals(LocalDate.of(2026, 8, 31), PeriodType.MONTHLY.windowEnd(end, LocalDate.of(2026, 8, 15)));
            assertEquals(end, PeriodType.MONTHLY.windowEnd(end, LocalDate.of(2026, 9, 5)));
        }
    }

    @Nested
    class 캠페인_상태 {

        @Test
        void 초안만_비공개다() {
            assertFalse(CampaignStatus.DRAFT.isPubliclyVisible());
            assertTrue(CampaignStatus.RUNNING.isPubliclyVisible());
            assertTrue(CampaignStatus.CLOSED.isPubliclyVisible());
        }
    }

    @Nested
    class 배너 {

        @Test
        void 둘_다_없으면_공유_빈_값을_돌려준다() {
            assertSame(CampaignBanner.empty(), CampaignBanner.of(null, null));
            assertNull(CampaignBanner.empty().pcImageUrl());
            assertNull(CampaignBanner.empty().mobileImageUrl());
        }

        @Test
        void 하나라도_있으면_새_값이다() {
            assertEquals("pc.png", CampaignBanner.of("pc.png", null).pcImageUrl());
            assertEquals("mo.png", CampaignBanner.of(null, "mo.png").mobileImageUrl());
        }
    }

    @Nested
    class 출석_문구 {

        private final AttendanceMessages messages =
                new AttendanceMessages("곧 시작", "진행 중", "달성 축하", "종료");

        @Test
        void 종료가_다른_모든_상태보다_우선한다() {
            assertEquals("종료", messages.forState(CampaignStatus.CLOSED, false, false));
            assertEquals("종료", messages.forState(CampaignStatus.CLOSED, true, true));
        }

        @Test
        void 시작_전이면_시작_전_문구다() {
            assertEquals("곧 시작", messages.forState(CampaignStatus.RUNNING, false, false));
        }

        @Test
        void 달성_여부로_진행_문구가_갈린다() {
            assertEquals("진행 중", messages.forState(CampaignStatus.RUNNING, true, false));
            assertEquals("달성 축하", messages.forState(CampaignStatus.RUNNING, true, true));
        }

        @Test
        void 빈_문구는_전부_null_이다() {
            assertNull(AttendanceMessages.empty().forState(CampaignStatus.RUNNING, true, true));
        }
    }

    @Nested
    class 참여_조건과_지급_방식 {

        @Test
        void 하루_1회는_날짜가_슬롯이고_기간_1회는_고정값이다() {
            LocalDate day = LocalDate.of(2026, 8, 12);

            assertEquals("2026-08-12", EntryCondition.PER_DAY.slotKey(day));
            assertEquals("ALL", EntryCondition.PER_PERIOD.slotKey(day));
        }

        @Test
        void 즉시_지급만_isImmediate_다() {
            assertTrue(BenefitType.IMMEDIATE.isImmediate());
            assertFalse(BenefitType.BATCH.isImmediate());
        }

        @ParameterizedTest
        @EnumSource(RewardSource.class)
        void 보상_출처는_이름으로_저장된다(RewardSource source) {
            assertEquals(source, RewardSource.valueOf(source.name()));
        }

        @ParameterizedTest
        @EnumSource(RewardStatus.class)
        void 보상_상태는_이름으로_저장된다(RewardStatus status) {
            assertEquals(status, RewardStatus.valueOf(status.name()));
        }
    }

    @Nested
    class 출석_기록 {

        private final AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        @Test
        void 캠페인_스냅샷을_같이_남긴다() {
            LocalDate on = LocalDate.of(2026, 8, 12);

            AttendanceRecord record = AttendanceRecord.of(UUID.randomUUID(), campaign, "member-1", on);

            assertEquals(campaign.id(), record.campaignId());
            assertEquals("8월 출석", record.campaignNameSnapshot());
            assertEquals(StreakRule.CONSECUTIVE, record.streakRuleSnapshot());
            assertEquals(campaign.windowStart(on), record.periodStartSnapshot());
            assertEquals(campaign.windowEnd(on), record.periodEndSnapshot());
            assertEquals(campaign.dailyRewardPoints(), record.dailyRewardPoints());
        }

        @Test
        void 필수값이_없으면_거절한다() {
            UUID id = UUID.randomUUID();
            LocalDate on = LocalDate.of(2026, 8, 12);

            assertThrows(IllegalArgumentException.class, () -> new AttendanceRecord(
                    null, campaign.id(), "m", on, BigDecimal.ZERO, "n", StreakRule.EVERY_DAY, on, on));
            assertThrows(IllegalArgumentException.class, () -> new AttendanceRecord(
                    id, null, "m", on, BigDecimal.ZERO, "n", StreakRule.EVERY_DAY, on, on));
            assertThrows(IllegalArgumentException.class, () -> new AttendanceRecord(
                    id, campaign.id(), " ", on, BigDecimal.ZERO, "n", StreakRule.EVERY_DAY, on, on));
            assertThrows(IllegalArgumentException.class, () -> new AttendanceRecord(
                    id, campaign.id(), "m", null, BigDecimal.ZERO, "n", StreakRule.EVERY_DAY, on, on));
            assertThrows(IllegalArgumentException.class, () -> new AttendanceRecord(
                    id, campaign.id(), "m", on, null, "n", StreakRule.EVERY_DAY, on, on));
            assertThrows(IllegalArgumentException.class, () -> new AttendanceRecord(
                    id, campaign.id(), "m", on, new BigDecimal("-1"), "n", StreakRule.EVERY_DAY, on, on));
        }
    }

    @Nested
    class 목표_달성_기록 {

        private final AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        @Test
        void 목표_보상을_그대로_복사한다() {
            LocalDate on = LocalDate.of(2026, 8, 12);

            AttendanceAchievement achievement =
                    AttendanceAchievement.of(UUID.randomUUID(), campaign, "member-1", on);

            assertEquals(campaign.id(), achievement.campaignId());
            assertEquals(on, achievement.achievedOn());
            assertEquals(campaign.goalRewardPoints(), achievement.rewardPoints());
        }

        @Test
        void 필수값이_없으면_거절한다() {
            UUID id = UUID.randomUUID();
            LocalDate on = LocalDate.of(2026, 8, 12);

            assertThrows(IllegalArgumentException.class,
                    () -> new AttendanceAchievement(null, campaign.id(), "m", on, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> new AttendanceAchievement(id, null, "m", on, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> new AttendanceAchievement(id, campaign.id(), "", on, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> new AttendanceAchievement(id, campaign.id(), "m", null, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> new AttendanceAchievement(id, campaign.id(), "m", on, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new AttendanceAchievement(id, campaign.id(), "m", on, new BigDecimal("-1")));
        }
    }

    @Nested
    class 럭키박스_참여_기록 {

        private final LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();

        @Test
        void 경품_내용을_값으로_복사해_둔다() {
            LocalDate on = LocalDate.of(2026, 8, 12);
            LuckyboxPrize prize = MarketingFixtures.pointPrize(campaign.id(), "500", 0.3d, 1);

            LuckyboxDraw draw = LuckyboxDraw.of(UUID.randomUUID(), campaign, "member-1", prize, on);

            assertEquals(prize.id(), draw.prizeId());
            assertEquals(PrizeType.POINT, draw.prizeType());
            assertEquals(new BigDecimal("500"), draw.rewardPoints());
            assertEquals(on, draw.drawnOn());
            assertEquals("2026-08-12", draw.entrySlot());
            assertNotNull(draw.drawnAt());
            assertTrue(draw.grantsPoints());
        }

        @Test
        void 텍스트_경품은_포인트를_만들지_않는다() {
            LocalDate on = LocalDate.of(2026, 8, 12);
            LuckyboxPrize prize = MarketingFixtures.textPrize(campaign.id(), "스티커", 0.7d, 2);

            LuckyboxDraw draw = LuckyboxDraw.of(UUID.randomUUID(), campaign, "member-1", prize, on);

            assertEquals("스티커", draw.textReward());
            assertFalse(draw.grantsPoints());
        }

        @Test
        void 필수값이_없으면_거절한다() {
            UUID id = UUID.randomUUID();
            LocalDate on = LocalDate.of(2026, 8, 12);

            assertThrows(IllegalArgumentException.class, () -> new LuckyboxDraw(
                    null, campaign.id(), "m", id, PrizeType.TEXT, null, "t", on, null, "ALL"));
            assertThrows(IllegalArgumentException.class, () -> new LuckyboxDraw(
                    id, null, "m", id, PrizeType.TEXT, null, "t", on, null, "ALL"));
            assertThrows(IllegalArgumentException.class, () -> new LuckyboxDraw(
                    id, campaign.id(), " ", id, PrizeType.TEXT, null, "t", on, null, "ALL"));
            assertThrows(IllegalArgumentException.class, () -> new LuckyboxDraw(
                    id, campaign.id(), "m", id, null, null, "t", on, null, "ALL"));
            assertThrows(IllegalArgumentException.class, () -> new LuckyboxDraw(
                    id, campaign.id(), "m", id, PrizeType.TEXT, null, "t", null, null, "ALL"));
            assertThrows(IllegalArgumentException.class, () -> new LuckyboxDraw(
                    id, campaign.id(), "m", id, PrizeType.TEXT, null, "t", on, null, " "));
        }
    }
}
