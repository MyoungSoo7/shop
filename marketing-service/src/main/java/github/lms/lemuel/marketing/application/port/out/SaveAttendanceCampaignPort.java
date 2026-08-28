package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.AttendanceCampaign;

/** 출석 캠페인 저장. */
public interface SaveAttendanceCampaignPort {
    AttendanceCampaign save(AttendanceCampaign campaign);
}
