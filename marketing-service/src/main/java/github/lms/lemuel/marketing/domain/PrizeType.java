package github.lms.lemuel.marketing.domain;

/** 경품의 종류. */
public enum PrizeType {

    /** 포인트 지급 — order-service 포인트 원장에 적립을 요청한다. */
    POINT,

    /**
     * 문구만. 꽝, 쿠폰코드 안내, 오프라인 교환권 같은 것들.
     *
     * <p>원장을 건드리지 않으므로 보상 요청도 만들지 않는다. 레거시는 이 구분 없이
     * 모든 당첨에 마일리지 지급 경로를 태웠고, 금액이 0 인 지급 이력이 원장에 쌓였다.
     */
    TEXT;

    public boolean grantsPoints() {
        return this == POINT;
    }
}
