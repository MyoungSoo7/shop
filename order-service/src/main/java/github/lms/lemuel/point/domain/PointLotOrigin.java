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
    /**
     * 이벤트 프로모션 보상 — 출석체크·럭키박스 당첨 등.
     *
     * <p>{@code MANUAL_GRANT} 와 굳이 나눈 이유는 회계가 아니라 <b>추적</b>이다. 둘 다 판촉비로
     * 같은 분개를 타지만, 수기 지급은 사람이 한 건씩 누른 것이고 이쪽은 marketing-service 가
     * 이벤트로 요청한 것이다. 캠페인 하나가 원장에 얼마를 태웠는지 물으려면 출처가 갈려 있어야
     * 하고, 반대로 판촉비가 튀었을 때 "누가 눌렀나" 와 "어떤 캠페인이 돌고 있나" 는 완전히
     * 다른 조사다. 한 값에 뭉치면 그 질문을 원장에 던질 수 없다.
     *
     * <p>{@code referenceType} 은 보상의 출처(ATTENDANCE_DAILY·ATTENDANCE_GOAL·LUCKYBOX),
     * {@code referenceId} 는 보상 UUID 다 — 그 짝이 적립의 멱등키이고, 같은 짝이 다시 와도
     * 로트는 하나다. marketing 은 되돌아오는 {@code lemuel.point.granted} 에서 그 짝을 보고
     * 자기 보상을 확정한다.
     */
    PROMOTION_REWARD(true),
    /** 환불로 되돌려 준 포인트인데 원 로트가 이미 소멸·소진되어 새로 발급한 경우. */
    REFUND_RESTORE(false),
    /**
     * 회원 간 선물로 <b>받은</b> 포인트.
     *
     * <p>판촉성이 아니다. 회사가 새로 얹어 준 몫이 아니라 이미 인식된 부채가 주인만 바꾼 것이라,
     * 여기에 판촉비를 다시 잡으면 같은 포인트에 비용이 두 번 계상된다. 보낸 이 쪽의 USE 와
     * 받는 이 쪽의 GRANT 는 한 트랜잭션에서 짝을 이루므로 부채 총액은 변하지 않는다.
     */
    TRANSFER_IN(false);

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
