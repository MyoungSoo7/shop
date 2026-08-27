package github.lms.lemuel.marketing.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 어떤 날을 출석으로 인정할지.
 *
 * <p>레거시는 이걸 {@code EVENT_DAY_TYPE} 에 'ED'/'WD'/'WE' 로 넣고 200줄짜리 Oracle 윈도우 함수
 * CTE 안에서 CASE 로 분기했다. 그 CASE 에는 'ED' 와 'WD' 가지만 있고 'WE' 도 ELSE 도 없었다 —
 * 주말 캠페인의 연속 출석일수가 항상 0 으로 나왔다는 뜻이다. NULL 이 0 으로 집계돼 에러 없이
 * "아직 하루도 안 왔습니다" 가 떴으니 아무도 몰랐다.
 *
 * <p>규칙을 SQL 에서 꺼내 여기에 둔 이유가 그거다. enum 은 가지를 빠뜨릴 수 없고,
 * {@link AttendanceStreak} 테스트가 세 규칙을 모두 돌린다.
 */
public enum DayTypeRule {

    /** 전일. */
    EVERY_DAY {
        @Override
        public boolean matches(LocalDate date) {
            return true;
        }
    },

    /** 평일만 — 토·일 출석은 집계에 넣지 않는다. */
    WEEKDAY {
        @Override
        public boolean matches(LocalDate date) {
            DayOfWeek day = date.getDayOfWeek();
            return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
        }
    },

    /** 주말만. */
    WEEKEND {
        @Override
        public boolean matches(LocalDate date) {
            DayOfWeek day = date.getDayOfWeek();
            return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        }
    };

    /** 이 날짜가 출석 인정 대상인가. */
    public abstract boolean matches(LocalDate date);

    /**
     * 이 규칙에서 {@code date} 바로 앞의 인정 대상 날짜.
     *
     * <p>연속 판정이 "어제 왔나" 가 아니라 "직전 인정일에 왔나" 여야 하는 이유가 여기 있다.
     * 평일 캠페인에서 금요일 다음 인정일은 월요일이고, 주말에 안 온 것은 결석이 아니다.
     * 최대 7번이면 반드시 다음 인정일을 만난다 — 모든 규칙이 한 주 안에 최소 하루를 포함한다.
     */
    public LocalDate previousQualifyingDay(LocalDate date) {
        LocalDate cursor = date.minusDays(1);
        for (int i = 0; i < 7; i++) {
            if (matches(cursor)) {
                return cursor;
            }
            cursor = cursor.minusDays(1);
        }
        throw new IllegalStateException("인정 대상 날짜를 7일 안에 찾지 못했다: rule=" + name() + ", from=" + date);
    }
}
