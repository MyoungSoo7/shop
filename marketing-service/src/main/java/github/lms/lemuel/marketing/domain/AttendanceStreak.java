package github.lms.lemuel.marketing.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 출석 집계 결과 — 누적 일수와 현재 연속 일수.
 *
 * <p>레거시에서 이 계산은 {@code selectAttendanceYCount} 라는 200줄짜리 Oracle 전용 SQL 이었다.
 * {@code ROW_NUMBER() OVER (...)} 두 벌과 날짜 그룹핑 CTE 로 연속 구간을 잘라 세는, 읽을 수는
 * 있지만 고칠 수는 없는 종류의 쿼리였다. 실제로 그 안에는 주말 규칙 가지가 통째로 빠져 있었고
 * (자세한 건 {@link DayTypeRule}), 결과가 0 이라 실패로 보이지 않아 오래 남아 있었다.
 *
 * <p>순수 함수로 옮긴 값이 두 개다. 하나는 DB 없이 테스트할 수 있다는 것, 다른 하나는
 * Oracle 을 떠날 수 있다는 것이다 — 저 쿼리는 PostgreSQL 에서 그대로 돌지 않는다.
 */
public record AttendanceStreak(int total, int current) {

    private static final AttendanceStreak NONE = new AttendanceStreak(0, 0);

    public AttendanceStreak {
        if (total < 0 || current < 0) {
            throw new IllegalArgumentException("출석 일수는 음수일 수 없다: total=" + total + ", current=" + current);
        }
        if (current > total) {
            throw new IllegalArgumentException("연속 일수가 누적 일수보다 클 수 없다: total=" + total + ", current=" + current);
        }
    }

    public static AttendanceStreak none() {
        return NONE;
    }

    /**
     * 출석한 날짜들에서 누적·연속 일수를 센다.
     *
     * <p>{@code total} 은 규칙에 맞는 출석일 수. {@code current} 는 <b>가장 최근 출석일부터</b>
     * 뒤로 인정일을 밟아 가며 끊기지 않은 구간의 길이다. "오늘부터" 가 아니라 "마지막 출석일부터"
     * 인 게 중요하다 — 오늘 아직 출석 안 한 사람에게 화면은 어제까지의 연속 기록을 보여줘야 한다.
     * 오늘까지 이어지는지는 부르는 쪽이 마지막 출석일을 보고 판단한다.
     *
     * @param attendedDates 출석한 날짜(중복·정렬 무관, null 원소 불가)
     * @param rule          어떤 날을 인정할지
     */
    public static AttendanceStreak evaluate(Collection<LocalDate> attendedDates, DayTypeRule rule) {
        Objects.requireNonNull(attendedDates, "attendedDates");
        Objects.requireNonNull(rule, "rule");

        SortedSet<LocalDate> qualifying = new TreeSet<>();
        for (LocalDate date : attendedDates) {
            Objects.requireNonNull(date, "attendedDates 에 null 이 있다");
            if (rule.matches(date)) {
                qualifying.add(date);
            }
        }
        if (qualifying.isEmpty()) {
            return NONE;
        }

        int current = 0;
        LocalDate cursor = qualifying.last();
        while (qualifying.contains(cursor)) {
            current++;
            cursor = rule.previousQualifyingDay(cursor);
        }
        return new AttendanceStreak(qualifying.size(), current);
    }
}
