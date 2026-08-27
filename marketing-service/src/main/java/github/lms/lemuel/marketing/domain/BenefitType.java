package github.lms.lemuel.marketing.domain;

/** 당첨 보상을 언제 주는지. 레거시 {@code BENEFIT_TYPE} 의 1/2. */
public enum BenefitType {

    /** 즉시 — 추첨과 같은 트랜잭션에서 보상 요청을 낸다. */
    IMMEDIATE,

    /**
     * 일괄 — 캠페인의 {@code benefit_on} 에 몰아서 지급한다.
     *
     * <p>당첨 사실은 바로 남고 보상만 대기 상태(PENDING)로 있다가, 정산 스케줄러가 지급일에
     * 요청으로 바꾼다. 지급일이 비어 있으면 영원히 대기하므로 DB CHECK 로 막아 두었다.
     */
    BATCH;

    public boolean isImmediate() {
        return this == IMMEDIATE;
    }
}
