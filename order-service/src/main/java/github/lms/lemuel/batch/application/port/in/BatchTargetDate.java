package github.lms.lemuel.batch.application.port.in;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * "어느 날짜분 배치인가" 를 실제 기준시각으로 바꾼다.
 *
 * <p>레거시(ssgb2e-quartz)의 {@code SettlementTargetDateResolver} 가 갖고 있던 축이다.
 * 스케줄러가 {@code LocalDateTime.now()} 만 읽으면 <b>놓친 날을 다시 돌릴 방법이 없다</b> —
 * 시각이 코드 안에 갇혀 있기 때문이다. 기준시각을 인자로 끌어내야 재실행이 가능해진다.
 *
 * <p>날짜분의 기준시각은 <b>그 날의 끝</b>이다. "9월 1일분 소멸 배치" 는 9월 1일이 끝나는
 * 시점까지 만료된 것을 처리한다는 뜻이지, 9월 1일 0시 기준이 아니다.
 */
public final class BatchTargetDate {

    /** 배치의 기준 시간대. 스케줄러 {@code @Scheduled(zone = "Asia/Seoul")} 과 같아야 한다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private BatchTargetDate() {
    }

    /** 그 날짜의 끝(23:59:59.999999999). */
    public static LocalDateTime endOf(LocalDate targetDate) {
        return targetDate.atTime(LocalTime.MAX);
    }

    /** 그 날짜의 끝을 오프셋까지 붙여서. */
    public static OffsetDateTime endOfWithOffset(LocalDate targetDate) {
        return endOf(targetDate).atZone(ZONE).toOffsetDateTime();
    }

    /** 그 날짜의 시작(00:00). 창(window) 재실행의 하한으로 쓴다. */
    public static LocalDateTime startOf(LocalDate targetDate) {
        return targetDate.atStartOfDay();
    }

    /** 다음 날짜의 시작. 창의 상한으로 쓴다 — 상한을 배타적으로 두어 날짜 경계가 겹치지 않게 한다. */
    public static LocalDateTime startOfNextDay(LocalDate targetDate) {
        return targetDate.plusDays(1).atStartOfDay();
    }

    /**
     * 그 날짜의 특정 시각을 오프셋까지 붙여서 — <b>그 날 배치가 실제로 돌던 순간</b>을 되살린다.
     *
     * <p>창 경계를 실행 시각 기준으로 잡는 배치(만료 예고처럼 "지금부터 7일 뒤")는 자정으로 되돌리면
     * 그 날 실제로 나갔어야 할 집합과 다른 집합이 나온다. 그런 배치의 재실행은 이쪽을 쓴다.
     */
    public static OffsetDateTime atHourWithOffset(LocalDate targetDate, int hour) {
        return targetDate.atTime(hour, 0).atZone(ZONE).toOffsetDateTime();
    }

    /** 스케줄 실행(=오늘)이 기록에 남길 날짜. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
