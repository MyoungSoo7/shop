package github.lms.lemuel.point.domain;

/**
 * 포인트 로트 상태.
 *
 * <p>전이: {@code ACTIVE → EXHAUSTED}(잔량 0 소진) / {@code ACTIVE → EXPIRED}(유효기간 경과) /
 * {@code ACTIVE → REVOKED}(적립 취소). 종단 상태에서 되살리지 않는다 — 되돌릴 일이 있으면
 * 신규 로트를 발급한다(원장 역분개 원칙과 같은 이유).
 */
public enum PointLotStatus {
    ACTIVE,
    EXHAUSTED,
    EXPIRED,
    REVOKED;

    /** 사용 가능한 재원인가. */
    public boolean isConsumable() {
        return this == ACTIVE;
    }

    /** 더 이상 전이하지 않는 종단 상태인가. */
    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
