package github.lms.lemuel.marketing.domain;

import java.time.LocalDate;

/** 얼마나 자주 참여할 수 있는지. 레거시 {@code EVENT_CONDITION} 의 1/2. */
public enum EntryCondition {

    /** 하루 한 번. */
    PER_DAY,

    /** 기간 중 한 번. */
    PER_PERIOD;

    /**
     * 참여 슬롯 키 — {@code (campaign_id, member_ref, entry_slot)} 유니크 인덱스의 세 번째 값.
     *
     * <p>조건에 따라 유니크 범위가 달라지는 걸 표현하는 방법이 두 가지였다. 부분 인덱스는
     * 캠페인 행의 조건을 봐야 하는데 인덱스는 다른 테이블을 볼 수 없다. 그래서 범위를 값으로
     * 만들었다 — 하루 제한이면 날짜가, 기간 제한이면 고정 문자열이 슬롯이 된다.
     */
    public String slotKey(LocalDate on) {
        return this == PER_DAY ? on.toString() : "ALL";
    }
}
