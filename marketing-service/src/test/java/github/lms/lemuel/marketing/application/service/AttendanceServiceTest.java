package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.dto.AttendanceBoardView;
import github.lms.lemuel.marketing.application.port.dto.AttendanceDayView;
import github.lms.lemuel.marketing.application.port.dto.CheckInResultView;
import github.lms.lemuel.marketing.application.port.out.AttendanceAchievementPort;
import github.lms.lemuel.marketing.application.port.out.AttendanceRecordPort;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.domain.AttendanceAchievement;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.AttendanceRecord;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.StreakRule;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 출석체크 유스케이스.
 *
 * <p>확인하는 것은 <b>순서</b>다 — 참여 가능한지 묻고, 기록을 남기고, 집계해서, 보상을 낸다.
 * 그리고 캠페인을 고르는 규칙: 레거시는 정렬 없는 {@code ROWNUM = 1} 이라 같은 기간에 캠페인이
 * 둘이면 실행할 때마다 다른 게 떴다.
 */
class AttendanceServiceTest {

    private static final String MEMBER = MarketingFixtures.MEMBER;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    private LoadAttendanceCampaignPort loadPort;
    private AttendanceRecordPort recordPort;
    private AttendanceAchievementPort achievementPort;
    private RewardIssuer rewardIssuer;
    private AttendanceService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadAttendanceCampaignPort.class);
        recordPort = mock(AttendanceRecordPort.class);
        achievementPort = mock(AttendanceAchievementPort.class);
        rewardIssuer = mock(RewardIssuer.class);
        service = new AttendanceService(loadPort, recordPort, achievementPort, rewardIssuer);
    }

    // ------------------------------------------------------------ 캠페인 선택

    @Test
    void 캠페인_id_가_있으면_그것을_쓴다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.findAttendedDates(any(), any(), any(), any())).thenReturn(List.of());
        when(achievementPort.findAchievements(any(), any(), any(), any())).thenReturn(List.of());

        AttendanceBoardView view = service.board(campaign.id(), MEMBER, TODAY);

        assertEquals(campaign.id(), view.campaignId());
        verify(loadPort, never()).findRunningOn(any());
    }

    @Test
    void 없는_캠페인_id_는_거절한다() {
        UUID unknown = UUID.randomUUID();
        when(loadPort.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(CampaignNotFoundException.class, () -> service.board(unknown, MEMBER, TODAY));
    }

    /** 레거시의 정렬 없는 {@code ROWNUM = 1} 을 시작일·이름 순으로 못 박았다. */
    @Test
    void 캠페인_id_가_없으면_먼저_시작한_캠페인을_고른다() {
        AttendanceCampaign later = MarketingFixtures.attendance(CampaignStatus.RUNNING, PeriodType.DAILY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2026, 8, 5), MarketingFixtures.END);
        AttendanceCampaign earlier = MarketingFixtures.runningAttendance();   // 8/1 시작
        when(loadPort.findRunningOn(TODAY)).thenReturn(List.of(later, earlier));
        when(recordPort.findAttendedDates(any(), any(), any(), any())).thenReturn(List.of());
        when(achievementPort.findAchievements(any(), any(), any(), any())).thenReturn(List.of());

        AttendanceBoardView view = service.board(null, MEMBER, TODAY);

        assertEquals(earlier.id(), view.campaignId());
    }

    @Test
    void 진행_중인_캠페인이_없으면_거절한다() {
        when(loadPort.findRunningOn(TODAY)).thenReturn(List.of());

        assertThrows(CampaignNotOpenException.class, () -> service.board(null, MEMBER, TODAY));
    }

    // ------------------------------------------------------------ 출석판 조회

    @Test
    void 출석판은_구간_전체를_하루씩_채운다() {
        AttendanceCampaign campaign = MarketingFixtures.attendance(CampaignStatus.RUNNING, PeriodType.DAILY,
                StreakRule.CONSECUTIVE, 3, DayTypeRule.WEEKDAY, new BigDecimal("10"), new BigDecimal("100"),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16));
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.findAttendedDates(eq(campaign.id()), eq(MEMBER), any(), any()))
                .thenReturn(List.of(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), TODAY));
        when(achievementPort.findAchievements(eq(campaign.id()), eq(MEMBER), any(), any()))
                .thenReturn(List.of(AttendanceAchievement.of(UUID.randomUUID(), campaign, MEMBER, TODAY)));

        AttendanceBoardView view = service.board(campaign.id(), MEMBER, TODAY);

        assertEquals(7, view.days().size());
        assertEquals(LocalDate.of(2026, 8, 10), view.windowStart());
        assertEquals(LocalDate.of(2026, 8, 16), view.windowEnd());
        assertEquals(3, view.attendedTotal());
        assertEquals(3, view.attendedStreak());
        assertEquals(1, view.achievedCount());
        assertTrue(view.checkedInToday());
        assertTrue(view.eligibleToday());       // 수요일
        assertEquals("달성 축하", view.message());
        assertEquals("pc.png", view.pcImageUrl());
        assertEquals("mo.png", view.mobileImageUrl());
        assertEquals("CONSECUTIVE", view.streakRule());
        assertEquals("DAILY", view.periodType());
        assertEquals("WEEKDAY", view.dayTypeRule());
        assertEquals(3, view.requiredCount());

        // 주말 이틀은 인정일이 아니다.
        AttendanceDayView saturday = view.days().stream()
                .filter(d -> d.date().equals(LocalDate.of(2026, 8, 15))).findFirst().orElseThrow();
        assertFalse(saturday.eligible());
        assertFalse(saturday.attended());
    }

    @Test
    void 시작_전이면_시작_전_문구를_보여_준다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.findAttendedDates(any(), any(), any(), any())).thenReturn(List.of());
        when(achievementPort.findAchievements(any(), any(), any(), any())).thenReturn(List.of());

        AttendanceBoardView view = service.board(campaign.id(), MEMBER, MarketingFixtures.START.minusDays(1));

        assertEquals("곧 시작", view.message());
        assertFalse(view.checkedInToday());
    }

    // ------------------------------------------------------------ 출석

    @Test
    void 출석하면_기록을_남기고_일일_보상을_낸다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recordPort.findAttendedDates(any(), any(), any(), any())).thenReturn(List.of(TODAY));
        when(rewardIssuer.issue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        CheckInResultView result = service.checkIn(campaign.id(), MEMBER, TODAY);

        assertEquals(TODAY, result.attendedOn());
        assertEquals(new BigDecimal("10"), result.dailyRewardPoints());
        assertEquals(1, result.attendedTotal());
        assertEquals(1, result.attendedStreak());
        assertFalse(result.goalReached());
        assertFalse(result.rewardPending());   // issue 가 null 을 돌려주면 대기 중인 보상도 없다

        verify(recordPort).save(any(AttendanceRecord.class));
        verify(rewardIssuer).issue(eq(RewardSource.ATTENDANCE_DAILY), any(), eq(campaign.id()),
                eq(campaign.name()), eq(MEMBER), eq(new BigDecimal("10")), isNull(),
                eq("8월 출석 [일일 출석]"), isNull());
        verify(achievementPort, never()).saveIfAbsent(any());
    }

    @Test
    void 일일_보상이_0_이면_보상을_내지_않는다() {
        AttendanceCampaign campaign = MarketingFixtures.attendance(CampaignStatus.RUNNING, PeriodType.DAILY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.ZERO, BigDecimal.ZERO,
                MarketingFixtures.START, MarketingFixtures.END);
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recordPort.findAttendedDates(any(), any(), any(), any())).thenReturn(List.of(TODAY));

        CheckInResultView result = service.checkIn(campaign.id(), MEMBER, TODAY);

        assertFalse(result.rewardPending());
        verify(rewardIssuer, never()).issue(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 목표를_채우면_달성_기록과_목표_보상이_같이_난다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();   // 3일 연속, 목표 100
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recordPort.findAttendedDates(any(), any(), any(), any()))
                .thenReturn(List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY));
        when(achievementPort.saveIfAbsent(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        when(rewardIssuer.issue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        CheckInResultView result = service.checkIn(campaign.id(), MEMBER, TODAY);

        assertTrue(result.goalReached());
        assertEquals(3, result.attendedStreak());
        assertEquals(new BigDecimal("100"), result.goalRewardPoints());
        verify(rewardIssuer).issue(eq(RewardSource.ATTENDANCE_GOAL), any(), eq(campaign.id()),
                eq(campaign.name()), eq(MEMBER), eq(new BigDecimal("100")), isNull(),
                eq("8월 출석 [목표 달성]"), isNull());
    }

    /** 같은 날 이미 달성 기록이 있으면 저장되지 않고 빈 값이 온다 — 보상도 이미 나갔다는 뜻이다. */
    @Test
    void 같은_날_두_번째_달성은_보상을_다시_내지_않는다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recordPort.findAttendedDates(any(), any(), any(), any()))
                .thenReturn(List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY));
        when(achievementPort.saveIfAbsent(any())).thenReturn(Optional.empty());

        CheckInResultView result = service.checkIn(campaign.id(), MEMBER, TODAY);

        assertFalse(result.goalReached());
        verify(rewardIssuer, never()).issue(eq(RewardSource.ATTENDANCE_GOAL), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void 보상이_실제로_발행되면_대기_표시가_켜진다() {
        AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
        when(loadPort.findById(campaign.id())).thenReturn(Optional.of(campaign));
        when(recordPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recordPort.findAttendedDates(any(), any(), any(), any())).thenReturn(List.of(TODAY));
        when(rewardIssuer.issue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(RewardGrant.requestNow(UUID.randomUUID(), RewardSource.ATTENDANCE_DAILY,
                        UUID.randomUUID(), campaign.id(), MEMBER, new BigDecimal("10"), null, "메모"));

        assertTrue(service.checkIn(campaign.id(), MEMBER, TODAY).rewardPending());
    }

    @Test
    void 진행_중이_아닌_캠페인에는_출석할_수_없다() {
        AttendanceCampaign closed = MarketingFixtures.attendance(CampaignStatus.CLOSED, PeriodType.DAILY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.TEN, BigDecimal.ZERO,
                MarketingFixtures.START, MarketingFixtures.END);
        when(loadPort.findById(closed.id())).thenReturn(Optional.of(closed));

        assertThrows(CampaignNotOpenException.class, () -> service.checkIn(closed.id(), MEMBER, TODAY));
        verify(recordPort, never()).save(any());
    }
}
