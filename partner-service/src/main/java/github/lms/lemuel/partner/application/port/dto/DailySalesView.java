package github.lms.lemuel.partner.application.port.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일자별 매출 한 행.
 *
 * <p>매출이 0 인 날은 <b>행이 없다</b>(빈 날을 만들어 채우지 않는다). 없는 날과 0원인 날을
 * 서버가 구분하지 못하는데 억지로 0 을 만들면, 아직 이벤트가 도착하지 않은 날까지 "0원 확정"
 * 으로 보이게 된다. 그래프의 빈칸 처리는 화면의 몫이다.
 *
 * @param date {@code partner_sales.sale_date} — 프로듀서가 보낸 결제시각(KST 로컬)의 날짜부분
 */
public record DailySalesView(
        LocalDate date,
        BigDecimal grossAmount,
        BigDecimal refundedAmount,
        BigDecimal netAmount,
        long orderCount) {
}
