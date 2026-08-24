package github.lms.lemuel.payment.domain;

/**
 * 분할결제 지불 수단 유형.
 *
 * <p>두 축으로 갈린다.
 *
 * <ul>
 *   <li>{@link #usesExternalPg()} — 외부 PG 호출 여부. POINT / GIFT_CARD 는 내부 잔액 차감이라
 *       PG 없이 처리되고, 그 외는 PG 어댑터를 경유한다.
 *   <li>{@link #awaitsDeposit()} — <b>돈이 나중에 들어오는가</b>. 가상계좌·무통장은 전문을 보내도
 *       구매자가 실제로 입금할 때까지 확정되지 않는다. 이 축이 결제를 즉시 캡처할지, 포인트를
 *       차감할지 선점할지, 미입금 만료 배치가 집어갈지를 좌우한다.
 * </ul>
 *
 * <p>두 축은 독립이다 — 가상계좌는 PG 를 쓰면서 동시에 입금을 기다린다.
 */
public enum TenderType {
    CARD(true, false),
    KAKAO_PAY(true, false),
    NAVER_PAY(true, false),
    PAYCO(true, false),
    SAMSUNG_PAY(true, false),
    /** 무통장 입금 — 전문 발행 후 실제 입금을 기다린다. */
    BANK_TRANSFER(true, true),
    /** 가상계좌 — 계좌 발급 후 실제 입금을 기다린다. */
    VIRTUAL_ACCOUNT(true, true),
    /** 멤버십 포인트 — 외부 PG 호출 없이 내부 잔액 차감 */
    POINT(false, false),
    /** 상품권 — 내부 잔액 차감 */
    GIFT_CARD(false, false);

    private final boolean usesExternalPg;
    private final boolean awaitsDeposit;

    TenderType(boolean usesExternalPg, boolean awaitsDeposit) {
        this.usesExternalPg = usesExternalPg;
        this.awaitsDeposit = awaitsDeposit;
    }

    public boolean usesExternalPg() {
        return usesExternalPg;
    }

    /**
     * 돈이 실제로 들어올 때까지 기다려야 하는 수단인가.
     *
     * <p>이 값이 {@code true} 면 결제는 발급 시점에 확정되지 않는다 — 즉시 캡처하면 <b>입금되지
     * 않은 주문이 정산 대상으로 넘어간다</b>.
     */
    public boolean awaitsDeposit() {
        return awaitsDeposit;
    }

    /**
     * 환불 우선순위 — 낮을수록 먼저 환불 (역순 환불 정책에서는 가장 마지막에 처리된 tender).
     * 일반적으로 외부 PG 가 먼저 환불되고 (실제 카드 결제 취소), 내부 잔액(포인트/상품권) 이 마지막에 복원.
     */
    public boolean isExternalFirst() {
        return usesExternalPg;
    }
}
