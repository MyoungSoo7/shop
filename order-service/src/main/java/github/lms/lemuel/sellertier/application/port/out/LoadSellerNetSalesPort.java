package github.lms.lemuel.sellertier.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 셀러별 12개월 결제 순액 (ADR 0031 결정 ①(a)).
 *
 * <p>집계원은 order 자기 DB 다 — settlement 확정 정산액을 쓰면 등급 컬럼 소유자(order)와 갈려
 * 역방향 이벤트 왕복이 생긴다. 등급은 구간 판정이라 결제 기준 근사로 충분하다(대사 대상 아님).
 */
public interface LoadSellerNetSalesPort {

    List<SellerNetSales> findNetSalesForLast12Months(LocalDate today, int limit);

    /** @param net12m CAPTURED 금액 − 환불액 */
    record SellerNetSales(Long sellerId, BigDecimal net12m) { }
}
