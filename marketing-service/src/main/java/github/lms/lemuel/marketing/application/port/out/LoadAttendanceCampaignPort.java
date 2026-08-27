package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.AttendanceCampaign;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 출석 캠페인 적재. */
public interface LoadAttendanceCampaignPort {

    Optional<AttendanceCampaign> findById(UUID campaignId);

    /** {@code on} 시점에 진행 중인(RUNNING + 기간 안) 캠페인들. 공개 목록 화면이 쓴다. */
    List<AttendanceCampaign> findRunningOn(LocalDate on);

    /** 관리자 목록 — 상태 무관 전량. */
    List<AttendanceCampaign> findAllForAdmin();
}
