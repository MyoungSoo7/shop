package github.lms.lemuel.marketing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 목표 달성 판정.
 *
 * <p>핵심은 {@code 배수로_판정한다} 와 {@code 목표를_넘긴_다음날은_달성이_아니다} 두 건이다.
 * 부등호({@code counted >= required})로 짜면 5일 목표 캠페인에서 6일째·7일째·8일째에도 계속
 * 달성이 되어 <b>매일</b> 목표 보상이 나간다. 캠페인 기간이 한 달이면 한 사람이 목표 보상을
 * 스물몇 번 받는다. 이건 배포 후 정산에서야 보이는 종류의 버그다.
 */
class StreakRuleTest {

    @ParameterizedTest
    @ValueSource(ints = {5, 10, 15, 20})
    void 배수마다_달성이다(int current) {
        AttendanceStreak streak = new AttendanceStreak(30, current);

        assertTrue(StreakRule.CONSECUTIVE.goalReached(streak, 5),
                current + "일은 5일 목표의 배수라 달성이다 — 10일 연속이면 두 번 받는 게 맞다");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 6, 7, 8, 9, 11})
    void 배수가_아니면_달성이_아니다(int current) {
        AttendanceStreak streak = new AttendanceStreak(30, current);

        assertFalse(StreakRule.CONSECUTIVE.goalReached(streak, 5));
    }

    @Test
    void 목표를_넘긴_다음날은_달성이_아니다() {
        // >= 로 짰다면 여기가 통과한다. 그게 매일 지급되는 버그다.
        assertFalse(StreakRule.CONSECUTIVE.goalReached(new AttendanceStreak(30, 6), 5));
        assertFalse(StreakRule.CONSECUTIVE.goalReached(new AttendanceStreak(30, 9), 5));
    }

    @Test
    void 연속은_current_를_누적은_total_을_본다() {
        // 누적 10일 · 연속 3일인 사람. 5일 목표에서 판정이 갈려야 한다.
        AttendanceStreak streak = new AttendanceStreak(10, 3);

        assertTrue(StreakRule.CUMULATIVE.goalReached(streak, 5), "누적 10 은 5 의 배수");
        assertFalse(StreakRule.CONSECUTIVE.goalReached(streak, 5), "연속은 3 이라 아직 아니다");
    }

    @Test
    void 목표_없음_규칙은_언제나_미달성이다() {
        assertFalse(StreakRule.EVERY_DAY.goalReached(new AttendanceStreak(100, 100), 5),
                "EVERY_DAY 는 일일 보상만 준다 — 목표 보상이라는 개념이 없다");
    }

    @ParameterizedTest
    @EnumSource(StreakRule.class)
    void 출석이_0_이면_어떤_규칙도_달성이_아니다(StreakRule rule) {
        assertFalse(rule.goalReached(AttendanceStreak.none(), 5),
                "0 % 5 == 0 이라 배수 판정만으로는 통과한다 — counted > 0 가드가 있어야 한다");
    }

    @ParameterizedTest
    @EnumSource(StreakRule.class)
    void 목표일수가_0_이하면_달성이_아니다(StreakRule rule) {
        AttendanceStreak streak = new AttendanceStreak(30, 30);

        assertFalse(rule.goalReached(streak, 0), "0 으로 나누면 ArithmeticException 이 난다");
        assertFalse(rule.goalReached(streak, -1));
    }
}
