package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.in.CreateAttendanceCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.ManageAttendanceCampaignUseCase;
import github.lms.lemuel.marketing.application.port.in.UpdateAttendanceCampaignCommand;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.SaveAttendanceCampaignPort;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.AttendanceMessages;
import github.lms.lemuel.marketing.domain.CampaignBanner;
import github.lms.lemuel.marketing.domain.exception.CampaignNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 출석 캠페인 운영 — 등록·수정·개시·종료. */
@Service
public class AttendanceCampaignAdminService implements ManageAttendanceCampaignUseCase {

    private final LoadAttendanceCampaignPort loadPort;
    private final SaveAttendanceCampaignPort savePort;

    public AttendanceCampaignAdminService(LoadAttendanceCampaignPort loadPort, SaveAttendanceCampaignPort savePort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
    }

    @Override
    @Transactional
    public UUID create(CreateAttendanceCampaignCommand c) {
        AttendanceCampaign campaign = AttendanceCampaign.draft(
                UUID.randomUUID(), c.tenantRef(), c.name(), c.periodType(), c.startsOn(), c.endsOn(),
                c.streakRule(), c.requiredCount(), c.dayTypeRule(), c.dailyRewardPoints(), c.goalRewardPoints(),
                c.rewardExpiresFrom(), c.rewardExpiresOn(),
                CampaignBanner.of(c.pcImageUrl(), c.mobileImageUrl()),
                new AttendanceMessages(c.messageBeforeStart(), c.messageRunning(), c.messageAchieved(),
                        c.messageClosed()),
                c.actor());
        // DRAFT 로 만든다 — 등록하는 순간 노출되던 레거시 동작을 일부러 바꿨다. 개시는 별도 호출이다.
        return savePort.save(campaign).id();
    }

    @Override
    @Transactional
    public void update(UpdateAttendanceCampaignCommand c) {
        AttendanceCampaign campaign = get(c.campaignId());
        campaign.update(c.name(), c.startsOn(), c.endsOn(), c.dailyRewardPoints(), c.goalRewardPoints(),
                CampaignBanner.of(c.pcImageUrl(), c.mobileImageUrl()),
                new AttendanceMessages(c.messageBeforeStart(), c.messageRunning(), c.messageAchieved(),
                        c.messageClosed()),
                c.actor());
        savePort.save(campaign);
    }

    @Override
    @Transactional
    public void open(UUID campaignId, String actor) {
        AttendanceCampaign campaign = get(campaignId);
        campaign.open(actor);
        savePort.save(campaign);
    }

    @Override
    @Transactional
    public void close(UUID campaignId, String actor) {
        AttendanceCampaign campaign = get(campaignId);
        campaign.close(actor);
        savePort.save(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceCampaign> list() {
        return loadPort.findAllForAdmin();
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceCampaign get(UUID campaignId) {
        return loadPort.findById(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
    }
}
