package github.lms.lemuel.marketing.domain;

/**
 * 목표 달성을 무엇으로 세는지. 레거시 {@code EVENT_CON} 의 'N'/'Y'/'C'.
 */
public enum StreakRule {

    /** 누적 N일 — 띄엄띄엄 와도 합계만 채우면 된다 (레거시 'N'). */
    CUMULATIVE,

    /** 연속 N일 — 인정일을 하루라도 건너뛰면 처음부터 (레거시 'Y'). */
    CONSECUTIVE,

    /** 목표 없음. 출석한 날마다 일일 보상만 준다 (레거시 'C'). */
    EVERY_DAY;

    /**
     * 이번 출석으로 목표를 새로 채웠는가.
     *
     * <p>배수로 판정한다 — 5일 연속이 목표인 캠페인에서 10일을 연속으로 오면 두 번 받는 게 맞다.
     * 부등호({@code >= required})로 판정하면 6일째·7일째에도 계속 달성이 되어 매일 목표 보상이
     * 나간다. 실제 지급은 여기서 한 번 더 막히지 않고 {@code attendance_achievements} 의
     * (캠페인, 회원, 달성일) 유니크 인덱스가 하루 두 번을 막는다.
     */
    public boolean goalReached(AttendanceStreak streak, int requiredCount) {
        if (this == EVERY_DAY || requiredCount <= 0) {
            return false;
        }
        int counted = (this == CONSECUTIVE) ? streak.current() : streak.total();
        return counted > 0 && counted % requiredCount == 0;
    }
}
