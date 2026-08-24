package github.lms.lemuel.point.domain;

/**
 * 포인트 로트의 출처.
 *
 * <p>출처를 로트에 남기는 이유는 회계다 — 현금 충전분은 부채 인식(DR CASH / CR POINT_LIABILITY)이고
 * 보너스·적립분은 판촉비 인식(DR POINT_PROMOTION_EXPENSE / CR POINT_LIABILITY)이라 GL 계정이 다르다.
 * 그래서 충전 원금과 충전 보너스는 <b>같은 로트에 합칠 수 없다</b>.
 */
public enum PointLotOrigin {
    /** 현금 충전 원금 — 고객이 실제로 낸 돈. */
    CHARGE_PRINCIPAL(false),
    /** 충전 보너스(선결제 인센티브) — 회사가 판촉비로 얹은 몫. */
    CHARGE_BONUS(true),
    /** 주문 구매 적립. */
    ORDER_EARN(true),
    /** 관리자 수기 지급. */
    MANUAL_GRANT(true),
    /** 환불로 되돌려 준 포인트인데 원 로트가 이미 소멸·소진되어 새로 발급한 경우. */
    REFUND_RESTORE(false);

    private final boolean promotional;

    PointLotOrigin(boolean promotional) {
        this.promotional = promotional;
    }

    /**
     * 회사가 비용으로 얹어 준 포인트인가 — GL 상대계정이 판촉비인지 현금인지를 가른다.
     * {@code REFUND_RESTORE} 는 이미 인식된 포인트를 되돌리는 것이라 새 비용이 아니다.
     */
    public boolean isPromotional() {
        return promotional;
    }
}
