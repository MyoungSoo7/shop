package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.domain.AttendanceCampaign;

import java.util.List;
import java.util.UUID;

/**
 * 출석 캠페인 운영.
 *
 * <p>조회가 도메인 애그리거트를 그대로 돌려준다. 운영자 화면은 캠페인의 거의 모든 필드를
 * 보여 주므로 별도 읽기 모델을 두면 필드를 두 벌 관리하게 되고, 실제로 레거시 관리자 화면이
 * 그렇게 어긋났다. 공개 API 는 다르다 — 그쪽은 확률·수량을 감춰야 해서 전용 뷰가 있다.
 */
public interface ManageAttendanceCampaignUseCase {

    UUID create(CreateAttendanceCampaignCommand command);

    void update(UpdateAttendanceCampaignCommand command);

    void open(UUID campaignId, String actor);

    void close(UUID campaignId, String actor);

    List<AttendanceCampaign> list();

    AttendanceCampaign get(UUID campaignId);
}
