package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.AttendanceAchievement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 목표 달성 기록 적재·저장. */
public interface AttendanceAchievementPort {

    List<AttendanceAchievement> findAchievements(UUID campaignId, String memberRef, LocalDate from, LocalDate to);

    /**
     * 저장한다. 같은 날 이미 달성 기록이 있으면 저장하지 않고 빈 값을 돌려준다.
     *
     * <p>빈 값을 돌려주는 이유는 출석 자체는 성공해야 하기 때문이다 — 목표 보상이 중복이라는
     * 이유로 출석까지 실패하면 사용자는 출석 버튼이 안 먹는 것으로 본다. 그래서 호출부는
     * 예외가 아니라 {@code Optional} 로 중복을 판단한다.
     *
     * <p><b>완벽한 방어는 아니다.</b> 구현은 조회 후 저장이고, 그 사이 다른 트랜잭션이 같은 행을
     * 넣으면 유니크 제약이 걸려 예외가 올라온다 — PostgreSQL 은 제약 위반 시 트랜잭션 전체를
     * 중단시키므로, 그 예외를 여기서 삼켜도 이미 진행 중인 트랜잭션은 되살릴 수 없다. 이때는 출석
     * 요청 전체가 롤백된다. 대신 <b>재시도가 수렴한다</b>: 다시 호출하면 출석 기록은 롤백돼 있어
     * 다시 저장되고, 달성 기록은 상대 트랜잭션이 커밋해 뒀으므로 조회에서 걸려 빈 값이 나온다.
     * 결과적으로 출석 1건 · 달성 1건 · 목표 보상 1건으로 정확히 수렴한다.
     *
     * <p>같은 사람이 같은 캠페인에서 같은 날 목표를 두 번 동시에 달성하려면 요청 두 개가 밀리초
     * 단위로 겹쳐야 한다. 그 경우를 savepoint 로 막을 수도 있지만, 그러려면 이 호출만 별도 전파
     * 경계로 빼야 하고 얻는 것은 재시도 한 번을 아끼는 것뿐이다.
     */
    Optional<AttendanceAchievement> saveIfAbsent(AttendanceAchievement achievement);
}
