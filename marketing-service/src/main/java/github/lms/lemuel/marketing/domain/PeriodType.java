package github.lms.lemuel.marketing.domain;

import java.time.LocalDate;

/**
 * 집계 판이 언제 초기화되는지. 레거시 {@code EDATE_TYPE} 의 'D'/'M'.
 */
public enum PeriodType {

    /** 기간 전체가 한 판. 시작일부터 종료일까지 누적·연속이 이어진다. */
    DAILY,

    /** 달마다 새 판. 8월 출석은 8월 1일에 0 부터 시작한다 (레거시 EDATE_MONTH). */
    MONTHLY;

    /** {@code on} 이 속한 집계 구간의 시작일. */
    public LocalDate windowStart(LocalDate campaignStart, LocalDate on) {
        if (this == DAILY) {
            return campaignStart;
        }
        LocalDate monthStart = on.withDayOfMonth(1);
        return monthStart.isBefore(campaignStart) ? campaignStart : monthStart;
    }

    /** {@code on} 이 속한 집계 구간의 종료일. */
    public LocalDate windowEnd(LocalDate campaignEnd, LocalDate on) {
        if (this == DAILY) {
            return campaignEnd;
        }
        LocalDate monthEnd = on.withDayOfMonth(on.lengthOfMonth());
        return monthEnd.isAfter(campaignEnd) ? campaignEnd : monthEnd;
    }
}
