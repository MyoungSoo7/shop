package github.lms.lemuel.marketing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 출석 집계 — 레거시 200줄 Oracle 쿼리가 하던 일.
 *
 * <p>이 테스트가 존재하는 이유가 {@code 주말_캠페인의_연속_출석이_0_이_아니다} 한 건이다.
 * 레거시 SQL 의 CASE 문에는 {@code 'ED'} 와 {@code 'WD'} 가지만 있고 {@code 'WE'} 도 ELSE 도
 * 없었다. 주말 캠페인은 연속 일수가 항상 NULL → 0 으로 나왔고, 에러가 아니라 "아직 출석
 * 기록이 없습니다" 로 보여서 오래 살아남았다.
 *
 * <p>기준 주(2026-08-24 월 ~ 2026-08-30 일)를 고정으로 쓴다. {@code LocalDate.now()} 를 쓰면
 * 테스트를 돌린 요일에 따라 결과가 바뀐다 — 주말에만 빨개지는 테스트는 아무도 안 고친다.
 */
class AttendanceStreakTest {

    private static final LocalDate MON = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);
    private static final LocalDate FRI = MON.plusDays(4);
    private static final LocalDate SAT = MON.plusDays(5);
    private static final LocalDate SUN = MON.plusDays(6);

    @Test
    void 출석이_없으면_0_0() {
        AttendanceStreak streak = AttendanceStreak.evaluate(List.of(), DayTypeRule.EVERY_DAY);

        assertEquals(0, streak.total());
        assertEquals(0, streak.current());
    }

    @Test
    void 중복_날짜는_한_번만_센다() {
        AttendanceStreak streak = AttendanceStreak.evaluate(
                List.of(MON, MON, TUE, TUE, TUE), DayTypeRule.EVERY_DAY);

        assertEquals(2, streak.total());
        assertEquals(2, streak.current());
    }

    @Test
    void 정렬되지_않은_입력도_같은_결과() {
        AttendanceStreak shuffled = AttendanceStreak.evaluate(
                List.of(WED, MON, FRI, TUE), DayTypeRule.EVERY_DAY);

        assertEquals(4, shuffled.total());
        assertEquals(1, shuffled.current(), "마지막 출석일이 금요일인데 목요일이 비어 거기서 끊긴다");
    }

    @Test
    void null_원소는_거절한다() {
        List<LocalDate> withNull = java.util.Arrays.asList(MON, null);

        assertThrows(NullPointerException.class,
                () -> AttendanceStreak.evaluate(withNull, DayTypeRule.EVERY_DAY));
    }

    @Nested
    @DisplayName("전일 규칙")
    class EveryDay {

        @Test
        void 하루라도_비면_연속이_끊긴다() {
            AttendanceStreak streak = AttendanceStreak.evaluate(
                    List.of(MON, TUE, THU, FRI), DayTypeRule.EVERY_DAY);

            assertEquals(4, streak.total());
            assertEquals(2, streak.current(), "수요일이 비었으므로 목·금 두 칸만 이어진다");
        }

        @Test
        void 연속은_오늘이_아니라_마지막_출석일부터_센다() {
            // 화·수 만 오고 그 뒤로 안 왔다. 오늘이 언제든 결과는 2 여야 한다 —
            // 화면은 "오늘 아직 출석 안 한 사람" 에게 어제까지의 기록을 보여줘야 한다.
            AttendanceStreak streak = AttendanceStreak.evaluate(List.of(TUE, WED), DayTypeRule.EVERY_DAY);

            assertEquals(2, streak.current());
        }
    }

    @Nested
    @DisplayName("평일 규칙")
    class Weekday {

        @Test
        void 주말_출석은_집계에서_빠진다() {
            AttendanceStreak streak = AttendanceStreak.evaluate(
                    List.of(FRI, SAT, SUN), DayTypeRule.WEEKDAY);

            assertEquals(1, streak.total(), "토·일 은 인정 대상이 아니다");
            assertEquals(1, streak.current());
        }

        @Test
        void 주말을_건너뛴_금요일과_월요일은_연속이다() {
            LocalDate nextMon = MON.plusDays(7);

            AttendanceStreak streak = AttendanceStreak.evaluate(
                    List.of(THU, FRI, nextMon), DayTypeRule.WEEKDAY);

            assertEquals(3, streak.total());
            assertEquals(3, streak.current(),
                    "평일 캠페인에서 금요일 다음 인정일은 월요일이다 — 주말에 안 온 건 결석이 아니다");
        }
    }

    @Nested
    @DisplayName("주말 규칙 — 레거시가 통째로 빠뜨린 가지")
    class Weekend {

        @Test
        void 주말_캠페인의_연속_출석이_0_이_아니다() {
            AttendanceStreak streak = AttendanceStreak.evaluate(List.of(SAT, SUN), DayTypeRule.WEEKEND);

            assertEquals(2, streak.total());
            assertEquals(2, streak.current());
        }

        @Test
        void 평일_출석은_집계에서_빠진다() {
            AttendanceStreak streak = AttendanceStreak.evaluate(
                    List.of(MON, TUE, WED, THU, FRI, SAT), DayTypeRule.WEEKEND);

            assertEquals(1, streak.total());
            assertEquals(1, streak.current());
        }

        @Test
        void 주중을_건너뛴_일요일과_다음_토요일은_연속이다() {
            LocalDate nextSat = SAT.plusDays(7);

            AttendanceStreak streak = AttendanceStreak.evaluate(
                    List.of(SAT, SUN, nextSat), DayTypeRule.WEEKEND);

            assertEquals(3, streak.total());
            assertEquals(3, streak.current());
        }

        @Test
        void 지난주_토요일과_이번주_토요일은_끊긴다() {
            LocalDate lastSat = SAT.minusDays(7);

            AttendanceStreak streak = AttendanceStreak.evaluate(List.of(lastSat, SAT), DayTypeRule.WEEKEND);

            assertEquals(2, streak.total());
            assertEquals(1, streak.current(), "사이의 일요일이 비었다");
        }
    }

    @Nested
    @DisplayName("직전 인정일")
    class PreviousQualifyingDay {

        @Test
        void 모든_규칙이_7일_안에_직전_인정일을_찾는다() {
            // previousQualifyingDay 는 7번 안에 못 찾으면 예외를 던진다. 한 주를 통째로 돌며
            // 세 규칙 × 7요일 = 21 경우가 전부 예외 없이 인정일을 반환하는지 본다.
            for (DayTypeRule rule : DayTypeRule.values()) {
                for (int offset = 0; offset < 7; offset++) {
                    LocalDate date = MON.plusDays(offset);
                    LocalDate previous = rule.previousQualifyingDay(date);

                    org.junit.jupiter.api.Assertions.assertTrue(rule.matches(previous),
                            rule + " 의 " + date + " 직전 인정일 " + previous + " 이 규칙에 안 맞는다");
                    org.junit.jupiter.api.Assertions.assertTrue(previous.isBefore(date),
                            "직전 인정일은 반드시 과거여야 한다");
                }
            }
        }

        @Test
        void 평일_규칙의_월요일_직전은_금요일() {
            assertEquals(FRI.minusDays(7), DayTypeRule.WEEKDAY.previousQualifyingDay(MON));
        }

        @Test
        void 주말_규칙의_토요일_직전은_지난_일요일() {
            assertEquals(SUN.minusDays(7), DayTypeRule.WEEKEND.previousQualifyingDay(SAT));
        }
    }

    @Nested
    @DisplayName("불변식")
    class Invariants {

        @Test
        void 음수는_만들_수_없다() {
            assertThrows(IllegalArgumentException.class, () -> new AttendanceStreak(-1, 0));
            assertThrows(IllegalArgumentException.class, () -> new AttendanceStreak(3, -1));
        }

        @Test
        void 연속이_누적보다_클_수_없다() {
            assertThrows(IllegalArgumentException.class, () -> new AttendanceStreak(2, 3));
        }

        @Test
        void 계산_결과는_항상_불변식을_지킨다() {
            Set<LocalDate> dates = Set.of(MON, WED, THU, SAT, SUN);

            for (DayTypeRule rule : DayTypeRule.values()) {
                AttendanceStreak streak = AttendanceStreak.evaluate(dates, rule);

                org.junit.jupiter.api.Assertions.assertTrue(streak.current() <= streak.total(),
                        rule + " 에서 연속(" + streak.current() + ") > 누적(" + streak.total() + ")");
            }
        }
    }
}
