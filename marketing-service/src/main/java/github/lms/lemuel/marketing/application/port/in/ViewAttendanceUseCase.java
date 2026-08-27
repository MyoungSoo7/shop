package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.application.port.dto.AttendanceBoardView;

import java.time.LocalDate;
import java.util.UUID;

/** 출석체크 현황 조회. */
public interface ViewAttendanceUseCase {

    /**
     * @param campaignId null 이면 오늘 진행 중인 출석 캠페인 중 가장 먼저 시작한 것을 고른다 —
     *                   레거시 화면이 캠페인 선택 없이 하나만 띄우던 동작을 유지한다.
     */
    AttendanceBoardView board(UUID campaignId, String memberRef, LocalDate on);
}
