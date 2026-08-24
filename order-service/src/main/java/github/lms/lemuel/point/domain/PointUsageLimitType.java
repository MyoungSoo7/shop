package github.lms.lemuel.point.domain;

/** 포인트 사용 상한의 계산 방식. */
public enum PointUsageLimitType {
    /** 상한 없음 — 잔액이 허용하는 만큼 결제액 전부를 덮을 수 있다. */
    NONE,
    /** 정액 상한 — "주문당 최대 N 포인트". */
    FIXED_AMOUNT,
    /** 비율 상한 — "결제액의 N% 까지". */
    ORDER_RATIO
}
