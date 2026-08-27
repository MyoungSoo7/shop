package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.AttendanceRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 출석 기록 적재·저장.
 *
 * <p>{@link #save}는 {@code (campaign_id, member_ref, attended_on)} 유니크 제약을 위반하면
 * {@link github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException} 로 바꿔
 * 던진다. 사전 조회로는 동시 요청 두 건을 막을 수 없기 때문에, 진짜 판정은 여기서 난다.
 */
public interface AttendanceRecordPort {

    /** 집계 구간 안의 출석일들. 연속·누적 계산의 입력이다. */
    List<LocalDate> findAttendedDates(UUID campaignId, String memberRef, LocalDate from, LocalDate to);

    List<AttendanceRecord> findRecords(UUID campaignId, String memberRef, LocalDate from, LocalDate to);

    AttendanceRecord save(AttendanceRecord record);
}
