package github.lms.lemuel.point.domain;

/**
 * 포인트 원장 엔트리 유형.
 *
 * <p>엔트리 금액은 <b>언제나 양수</b>이고 잔고에 미치는 방향은 이 유형이 결정한다
 * (deposit_entries 와 같은 규약 — 부호를 금액에 넣지 않는다).
 */
public enum PointEntryType {
    /** 적립·충전 — 잔고 증가. */
    GRANT(true),
    /** 사용(결제 차감) — 잔고 감소. */
    USE(false),
    /** 환불 복원 — 잔고 증가. */
    RESTORE(true),
    /** 유효기간 소멸 — 잔고 감소. */
    EXPIRE(false),
    /** 적립 취소(주문 취소 등) — 잔고 감소. */
    REVOKE(false);

    private final boolean increase;

    PointEntryType(boolean increase) {
        this.increase = increase;
    }

    /** 이 엔트리가 잔고를 늘리는가. */
    public boolean increasesBalance() {
        return increase;
    }

    /** 이 엔트리가 기존 로트를 소비하는가 — 소비 상세(PointLotConsumption)를 요구하는 유형. */
    public boolean consumesLots() {
        return !increase;
    }
}
