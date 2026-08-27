package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.dto.AttendanceBoardView;
import github.lms.lemuel.marketing.application.port.dto.AttendanceDayView;
import github.lms.lemuel.marketing.application.port.dto.CheckInResultView;
import github.lms.lemuel.marketing.application.port.in.CheckInUseCase;
import github.lms.lemuel.marketing.application.port.in.ViewAttendanceUseCase;
import github.lms.lemuel.marketing.application.port.out.AttendanceAchievementPort;
import github.lms.lemuel.marketing.application.port.out.AttendanceRecordPort;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.domain.AttendanceAchievement;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.AttendanceRecord;
import github.lms.lemuel.marketing.domain.AttendanceStreak;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 출석체크 — 조회와 출석.
 *
 * <p>레거시의 같은 기능은 컨트롤러 하나에 400줄쯤 있었고, 그 안에서 달력 HTML 을 만들고
 * 마일리지를 적립하고 세션에서 회원을 꺼냈다. 여기서 남은 것은 <b>순서</b>뿐이다 —
 * 참여 가능한지 묻고, 기록을 남기고, 집계해서, 보상을 낸다. 각 단계의 규칙은 도메인에 있다.
 */
@Service
public class AttendanceService implements ViewAttendanceUseCase, CheckInUseCase {

    private final LoadAttendanceCampaignPort loadCampaignPort;
    private final AttendanceRecordPort recordPort;
    private final AttendanceAchievementPort achievementPort;
    private final RewardIssuer rewardIssuer;

    public AttendanceService(LoadAttendanceCampaignPort loadCampaignPort,
                             AttendanceRecordPort recordPort,
                             AttendanceAchievementPort achievementPort,
                             RewardIssuer rewardIssuer) {
        this.loadCampaignPort = loadCampaignPort;
        this.recordPort = recordPort;
        this.achievementPort = achievementPort;
        this.rewardIssuer = rewardIssuer;
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceBoardView board(UUID campaignId, String memberRef, LocalDate on) {
        AttendanceCampaign campaign = resolve(campaignId, on);
        LocalDate windowStart = campaign.windowStart(on);
        LocalDate windowEnd = campaign.windowEnd(on);

        List<LocalDate> attended = recordPort.findAttendedDates(campaign.id(), memberRef, windowStart, windowEnd);
        AttendanceStreak streak = AttendanceStreak.evaluate(attended, campaign.dayTypeRule());
        int achievedCount = achievementPort
                .findAchievements(campaign.id(), memberRef, windowStart, windowEnd).size();

        Set<LocalDate> attendedSet = Set.copyOf(attended);
        List<AttendanceDayView> days = new ArrayList<>();
        for (LocalDate day = windowStart; !day.isAfter(windowEnd); day = day.plusDays(1)) {
            days.add(new AttendanceDayView(day, campaign.dayTypeRule().matches(day), attendedSet.contains(day)));
        }

        boolean started = !on.isBefore(campaign.startsOn());
        boolean checkedInToday = attendedSet.contains(on);
        String message = campaign.messages().forState(campaign.status(), started, achievedCount > 0);

        return new AttendanceBoardView(
                campaign.id(),
                campaign.name(),
                campaign.periodType().name(),
                campaign.streakRule().name(),
                campaign.dayTypeRule().name(),
                campaign.requiredCount(),
                campaign.startsOn(),
                campaign.endsOn(),
                windowStart,
                windowEnd,
                campaign.dailyRewardPoints(),
                campaign.goalRewardPoints(),
                streak.total(),
                streak.current(),
                achievedCount,
                checkedInToday,
                campaign.dayTypeRule().matches(on),
                message,
                campaign.banner().pcImageUrl(),
                campaign.banner().mobileImageUrl(),
                days);
    }

    @Override
    @Transactional
    public CheckInResultView checkIn(UUID campaignId, String memberRef, LocalDate on) {
        AttendanceCampaign campaign = resolve(campaignId, on);
        campaign.assertCheckInAllowed(on);

        // 중복 출석은 여기서 걸린다. 어댑터가 유니크 제약 위반을 AlreadyParticipatedException 으로
        // 바꿔 던지므로, "조회해서 없으면 저장" 이 아니라 "저장해 보고 튕기면 중복" 이다.
        AttendanceRecord record = recordPort.save(
                AttendanceRecord.of(UUID.randomUUID(), campaign, memberRef, on));

        boolean rewardPending = false;
        if (campaign.hasDailyReward()) {
            rewardPending |= rewardIssuer.issue(
                    RewardSource.ATTENDANCE_DAILY,
                    record.id(),
                    campaign.id(),
                    campaign.name(),
                    memberRef,
                    campaign.dailyRewardPoints(),
                    campaign.rewardExpiryFor(on),
                    campaign.name() + " [일일 출석]",
                    null) != null;
        }

        LocalDate windowStart = campaign.windowStart(on);
        LocalDate windowEnd = campaign.windowEnd(on);
        AttendanceStreak streak = AttendanceStreak.evaluate(
                recordPort.findAttendedDates(campaign.id(), memberRef, windowStart, windowEnd),
                campaign.dayTypeRule());

        boolean goalReached = false;
        if (campaign.goalReached(streak)) {
            // 같은 날 이미 달성 기록이 있으면 저장되지 않고 빈 값이 온다 — 보상도 이미 나갔다는 뜻이다.
            Optional<AttendanceAchievement> achievement = achievementPort.saveIfAbsent(
                    AttendanceAchievement.of(UUID.randomUUID(), campaign, memberRef, on));
            goalReached = achievement.isPresent();
            if (goalReached && campaign.hasGoalReward()) {
                rewardPending |= rewardIssuer.issue(
                        RewardSource.ATTENDANCE_GOAL,
                        achievement.get().id(),
                        campaign.id(),
                        campaign.name(),
                        memberRef,
                        campaign.goalRewardPoints(),
                        campaign.rewardExpiryFor(on),
                        campaign.name() + " [목표 달성]",
                        null) != null;
            }
        }

        return new CheckInResultView(
                on,
                campaign.dailyRewardPoints(),
                streak.total(),
                streak.current(),
                goalReached,
                campaign.goalRewardPoints(),
                rewardPending);
    }

    /**
     * 캠페인을 정한다.
     *
     * <p>{@code campaignId} 가 없으면 오늘 진행 중인 것 중 가장 먼저 시작한 캠페인을 고른다.
     * 레거시 화면은 캠페인 선택 개념이 없이 항상 하나만 띄웠고, 그 하나를 SQL 의
     * {@code ROWNUM = 1} 이 정했다 — 정렬 없이. 같은 기간에 캠페인이 둘이면 어느 쪽이 뜰지
     * 실행할 때마다 달랐다는 뜻이다. 여기서는 시작일·이름 순으로 못 박는다.
     */
    private AttendanceCampaign resolve(UUID campaignId, LocalDate on) {
        if (campaignId != null) {
            return loadCampaignPort.findById(campaignId)
                    .orElseThrow(() -> new CampaignNotFoundException(campaignId));
        }
        return loadCampaignPort.findRunningOn(on).stream()
                .min(Comparator.comparing(AttendanceCampaign::startsOn).thenComparing(AttendanceCampaign::name))
                .orElseThrow(() -> new CampaignNotOpenException("진행 중인 출석체크 이벤트가 없습니다"));
    }
}
