package github.lms.lemuel.partner.application.port.dto;

import java.math.BigDecimal;

/**
 * 기간 매출 요약.
 *
 * <p>{@code net = gross - refunded} 이며 <b>음수가 될 수 있다</b>. 이번 달에 지난달 결제분이
 * 환불되면 그렇다. 0 으로 깎지 않는 이유는, 깎는 순간 화면 합계와 실제 정산액이 어긋나고
 * 그 차이를 설명할 수 있는 사람이 없어지기 때문이다.
 *
 * @param orderCount 결제 건수(주문 건수가 아니다 — 한 주문이 분할 결제되면 2 로 센다)
 */
public record SalesSummaryView(
        BigDecimal grossAmount,
        BigDecimal refundedAmount,
        BigDecimal netAmount,
        long orderCount) {

    public static SalesSummaryView empty() {
        return new SalesSummaryView(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L);
    }
}
