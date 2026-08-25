package github.lms.lemuel.operation.dashboard.domain;

/**
 * "오늘 한눈에" 가 세는 지표.
 *
 * <p>목록이 이 넷인 이유는 <b>지금 실제로 발행되고 있는 이벤트</b>가 이 넷이기 때문이다. 보고
 * 싶은 것을 먼저 정하고 이벤트를 찾으면, 아무도 발행하지 않는 지표가 영원히 0 을 가리키는
 * 카드로 남는다 — 0 인지 고장인지 구분할 수 없는 숫자가 대시보드에서 가장 나쁘다.
 *
 * <p>{@link #hasAmount()} 가 금액 축의 유무를 정한다. 가입처럼 금액이 없는 지표는 합계 칸을
 * 아예 그리지 않는다. 0원으로 그리면 "오늘 가입 매출 0원"이라는 없는 사실이 생긴다.
 */
public enum DashboardMetric {

    ORDER_CREATED("오늘 주문", true),
    PAYMENT_CAPTURED("결제 완료", true),
    PAYMENT_REFUNDED("환불", true),
    USER_REGISTERED("신규 가입", false);

    private final String label;
    private final boolean hasAmount;

    DashboardMetric(String label, boolean hasAmount) {
        this.label = label;
        this.hasAmount = hasAmount;
    }

    public String label() {
        return label;
    }

    public boolean hasAmount() {
        return hasAmount;
    }
}
