package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import github.lms.lemuel.marketing.domain.exception.DayNotEligibleException;
import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 출석 캠페인 애그리거트.
 *
 * <p>레거시가 화면·컨트롤러·SQL 세 곳에 흩어 놓았던 판단이 여기 하나로 모였는지 확인한다.
 * 특히 {@code assertCheckInAllowed} 의 <b>순서</b>를 검사한다 — 종료된 캠페인에 "오늘은 인정일이
 * 아닙니다" 라고 답하면 사용자는 다음 주에 다시 온다.
 */
class AttendanceCampaignTest {

    private static final LocalDate START = MarketingFixtures.START;
    private static final LocalDate END = MarketingFixtures.END;

    private static AttendanceCampaign draft(String name, LocalDate startsOn, LocalDate endsOn,
                                            StreakRule rule, int requiredCount) {
        return AttendanceCampaign.draft(UUID.randomUUID(), "tenant-1", name, PeriodType.DAILY, startsOn, endsOn,
                rule, requiredCount, DayTypeRule.EVERY_DAY, BigDecimal.TEN, BigDecimal.ONE,
                null, null, null, null, "admin");
    }

    // ------------------------------------------------------------ 생성 규칙

    @Test
    void 초안은_DRAFT_로_만들어진다() {
        AttendanceCampaign campaign = draft("8월 출석", START, END, StreakRule.CONSECUTIVE, 3);

        assertEquals(CampaignStatus.DRAFT, campaign.status());
        assertEquals("tenant-1", campaign.tenantRef());
        assertEquals("admin", campaign.createdBy());
        assertEquals("admin", campaign.updatedBy());
        assertEquals(0L, campaign.version());
        assertEquals(PeriodType.DAILY, campaign.periodType());
        assertEquals(DayTypeRule.EVERY_DAY, campaign.dayTypeRule());
        assertEquals(3, campaign.requiredCount());
    }

    @Test
    void 배너와_문구가_없으면_빈_값으로_채운다() {
        AttendanceCampaign campaign = draft("8월 출석", START, END, StreakRule.EVERY_DAY, 0);

        assertEquals(CampaignBanner.empty(), campaign.banner());
        assertEquals(AttendanceMessages.empty(), campaign.messages());
    }

    @Test
    void id_와_이름이_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> AttendanceCampaign.draft(
                null, "t", "이름", PeriodType.DAILY, START, END, StreakRule.EVERY_DAY, 0,
                DayTypeRule.EVERY_DAY, null, null, null, null, null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> draft(" ", START, END, StreakRule.EVERY_DAY, 0));
    }

    @Test
    void 기간이_없거나_뒤집히면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> draft("이름", null, END, StreakRule.EVERY_DAY, 0));
        assertThrows(IllegalArgumentException.class, () -> draft("이름", START, null, StreakRule.EVERY_DAY, 0));
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", END, START, StreakRule.EVERY_DAY, 0));
    }

    @Test
    void 목표_일수가_음수면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> draft("이름", START, END, StreakRule.CUMULATIVE, -1));
    }

    /** 목표가 있는 규칙인데 목표 일수가 0 이면 아무도 달성하지 못한다 — 등록 시점에 막는다. */
    @Test
    void 목표형_규칙은_목표_일수가_0_이면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> draft("이름", START, END, StreakRule.CUMULATIVE, 0));
        assertThrows(IllegalArgumentException.class, () -> draft("이름", START, END, StreakRule.CONSECUTIVE, 0));
    }

    @Test
    void 전일형_규칙은_목표_일수가_0_이어도_된다() {
        assertEquals(0, draft("이름", START, END, StreakRule.EVERY_DAY, 0).requiredCount());
    }

    @Test
    void 보상_포인트가_음수면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> AttendanceCampaign.draft(
                UUID.randomUUID(), "t", "이름", PeriodType.DAILY, START, END, StreakRule.EVERY_DAY, 0,
                DayTypeRule.EVERY_DAY, new BigDecimal("-1"), null, null, null, null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> AttendanceCampaign.draft(
                UUID.randomUUID(), "t", "이름", PeriodType.DAILY, START, END, StreakRule.EVERY_DAY, 0,
                DayTypeRule.EVERY_DAY, null, new BigDecimal("-1"), null, null, null, null, "admin"));
    }

    @Test
    void 보상_포인트가_null_이면_0_이다() {
        AttendanceCampaign campaign = AttendanceCampaign.draft(
                UUID.randomUUID(), "t", "이름", PeriodType.DAILY, START, END, StreakRule.EVERY_DAY, 0,
                DayTypeRule.EVERY_DAY, null, null, null, null, null, null, "admin");

        assertEquals(BigDecimal.ZERO, campaign.dailyRewardPoints());
        assertEquals(BigDecimal.ZERO, campaign.goalRewardPoints());
        assertFalse(campaign.hasDailyReward());
        assertFalse(campaign.hasGoalReward());
    }

    // ------------------------------------------------------------ 참여 가능 여부

    @Test
    void 진행_중이_아니면_상태를_먼저_말한다() {
        AttendanceCampaign closed = MarketingFixtures.attendance(CampaignStatus.CLOSED, PeriodType.DAILY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.WEEKDAY, BigDecimal.TEN, BigDecimal.ZERO, START, END);

        // 토요일(= 인정일 아님) 이지만 나오는 말은 요일이 아니라 종료여야 한다.
        CampaignNotOpenException e = assertThrows(CampaignNotOpenException.class,
                () -> closed.assertCheckInAllowed(LocalDate.of(2026, 8, 8)));
        assertTrue(e.getMessage().contains("진행 중인 캠페인이 아닙니다"));
    }

    @Test
    void 기간_밖이면_거절한다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        assertThrows(CampaignNotOpenException.class, () -> campaign.assertCheckInAllowed(START.minusDays(1)));
        assertThrows(CampaignNotOpenException.class, () -> campaign.assertCheckInAllowed(END.plusDays(1)));
    }

    @Test
    void 인정일이_아니면_요일_규칙으로_거절한다() {
        AttendanceCampaign weekday = MarketingFixtures.attendance(CampaignStatus.RUNNING, PeriodType.DAILY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.WEEKDAY, BigDecimal.TEN, BigDecimal.ZERO, START, END);

        assertThrows(DayNotEligibleException.class,
                () -> weekday.assertCheckInAllowed(LocalDate.of(2026, 8, 8)));   // 토요일
        weekday.assertCheckInAllowed(LocalDate.of(2026, 8, 10));                 // 월요일 — 통과
    }

    // ------------------------------------------------------------ 집계 구간·목표

    @Test
    void 일간_캠페인의_집계_구간은_캠페인_전체다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        assertEquals(START, campaign.windowStart(LocalDate.of(2026, 8, 20)));
        assertEquals(END, campaign.windowEnd(LocalDate.of(2026, 8, 20)));
    }

    @Test
    void 월간_캠페인의_집계_구간은_그_달이되_캠페인_경계를_넘지_않는다() {
        AttendanceCampaign campaign = MarketingFixtures.attendance(CampaignStatus.RUNNING, PeriodType.MONTHLY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.TEN, BigDecimal.ZERO,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 9, 20));

        // 8월 판은 8/1 이 아니라 캠페인 시작일 8/10 부터다.
        assertEquals(LocalDate.of(2026, 8, 10), campaign.windowStart(LocalDate.of(2026, 8, 15)));
        assertEquals(LocalDate.of(2026, 8, 31), campaign.windowEnd(LocalDate.of(2026, 8, 15)));
        // 9월 판은 9/30 이 아니라 캠페인 종료일 9/20 까지다.
        assertEquals(LocalDate.of(2026, 9, 1), campaign.windowStart(LocalDate.of(2026, 9, 15)));
        assertEquals(LocalDate.of(2026, 9, 20), campaign.windowEnd(LocalDate.of(2026, 9, 15)));
    }

    @Test
    void 목표_달성_판정은_집계_규칙에_위임한다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();   // 3일 연속

        assertTrue(campaign.goalReached(new AttendanceStreak(3, 3)));
        assertFalse(campaign.goalReached(new AttendanceStreak(3, 2)));
    }

    // ------------------------------------------------------------ 보상 소멸일

    @Test
    void 소멸일이_없으면_무기한_로트다() {
        assertNull(MarketingFixtures.runningAttendance().rewardExpiryFor(LocalDate.of(2026, 8, 10)));
    }

    @Test
    void 소멸_정책_시작일_이전_지급분은_소멸일이_없다() {
        LocalDate from = LocalDate.of(2026, 8, 15);
        LocalDate expiresOn = LocalDate.of(2026, 12, 31);
        AttendanceCampaign campaign = AttendanceCampaign.rehydrate(UUID.randomUUID(), "t", "이름",
                PeriodType.DAILY, START, END, StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY,
                BigDecimal.TEN, BigDecimal.ZERO, from, expiresOn, CampaignStatus.RUNNING, null, null,
                "admin", "admin", 0L);

        assertNull(campaign.rewardExpiryFor(from.minusDays(1)));
        assertEquals(expiresOn, campaign.rewardExpiryFor(from));
        assertEquals(expiresOn, campaign.rewardExpiryFor(from.plusDays(1)));
    }

    @Test
    void 소멸_정책_시작일이_없으면_전량_소멸일을_갖는다() {
        LocalDate expiresOn = LocalDate.of(2026, 12, 31);
        AttendanceCampaign campaign = AttendanceCampaign.rehydrate(UUID.randomUUID(), "t", "이름",
                PeriodType.DAILY, START, END, StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY,
                BigDecimal.TEN, BigDecimal.ZERO, null, expiresOn, CampaignStatus.RUNNING, null, null,
                "admin", "admin", 0L);

        assertEquals(expiresOn, campaign.rewardExpiryFor(START));
    }

    // ------------------------------------------------------------ 상태 전이·수정

    @Test
    void 개시하면_RUNNING_이_되고_수정자가_남는다() {
        AttendanceCampaign campaign = draft("8월 출석", START, END, StreakRule.EVERY_DAY, 0);

        campaign.open("operator-2");

        assertEquals(CampaignStatus.RUNNING, campaign.status());
        assertEquals("operator-2", campaign.updatedBy());
    }

    @Test
    void 종료된_캠페인은_다시_열_수_없다() {
        AttendanceCampaign campaign = draft("8월 출석", START, END, StreakRule.EVERY_DAY, 0);
        campaign.close("operator-2");

        assertEquals(CampaignStatus.CLOSED, campaign.status());
        assertThrows(CampaignNotOpenException.class, () -> campaign.open("operator-3"));
    }

    @Test
    void 수정은_기간과_보상과_노출만_바꾼다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
        LocalDate newEnd = END.plusDays(10);

        campaign.update("9월까지 연장", START, newEnd, new BigDecimal("20"), new BigDecimal("200"),
                CampaignBanner.of("new-pc.png", null), new AttendanceMessages(null, "연장 진행 중", null, null),
                "operator-9");

        assertEquals("9월까지 연장", campaign.name());
        assertEquals(newEnd, campaign.endsOn());
        assertEquals(new BigDecimal("20"), campaign.dailyRewardPoints());
        assertEquals(new BigDecimal("200"), campaign.goalRewardPoints());
        assertEquals("new-pc.png", campaign.banner().pcImageUrl());
        assertEquals("연장 진행 중", campaign.messages().running());
        assertEquals("operator-9", campaign.updatedBy());
        // 집계 규칙은 소급 변경을 막기 위해 수정 대상이 아니다.
        assertEquals(StreakRule.CONSECUTIVE, campaign.streakRule());
        assertEquals(3, campaign.requiredCount());
    }

    @Test
    void 수정도_이름과_기간을_다시_검사한다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        assertThrows(IllegalArgumentException.class, () -> campaign.update(
                " ", START, END, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> campaign.update(
                "이름", END, START, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> campaign.update(
                "이름", null, END, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "admin"));
    }

    @Test
    void 수정에서_배너와_문구가_null_이면_빈_값이_된다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        campaign.update("이름", START, END, BigDecimal.ONE, BigDecimal.ONE, null, null, "admin");

        assertEquals(CampaignBanner.empty(), campaign.banner());
        assertEquals(AttendanceMessages.empty(), campaign.messages());
    }

    @Test
    void 되살린_캠페인은_영속_상태를_그대로_들고_온다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();

        assertEquals(3L, campaign.version());
        assertEquals("tenant-1", campaign.tenantRef());
        assertEquals("pc.png", campaign.banner().pcImageUrl());
        assertEquals("mo.png", campaign.banner().mobileImageUrl());
        assertNull(campaign.rewardExpiresFrom());
        assertNull(campaign.rewardExpiresOn());
        assertTrue(campaign.hasDailyReward());
        assertTrue(campaign.hasGoalReward());
    }
}
