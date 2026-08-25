package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.TenderRevenue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 기간 매출 집계 포트.
 *
 * <p>기간은 이미 정규화된 반개구간({@code from} 이상 {@code toExclusive} 미만)으로 받는다.
 * 경계를 어떻게 해석할지는 정책이라 서비스가 정하고, 어댑터는 다시 계산하지 않는다
 * ({@code SearchOrdersPort} 와 같은 관례).
 *
 * <p>세 질의를 하나로 합치지 않는 이유는 <b>시간축이 서로 다르기 때문</b>이다. 수납은
 * {@code payments.captured_at}, 환불은 {@code refunds.completed_at} 에 달린다. 한 번의
 * 조인으로 묶으면 8월에 팔려 9월에 환불된 건이 어느 달에도 정확히 서지 못한다.
 */
public interface LoadRevenueStatisticsPort {

    /** 일자별 수납(캡처) 건수·금액. 수납이 없던 날은 행이 없다. */
    List<DailyAmount> capturesByDay(LocalDateTime from, LocalDateTime toExclusive);

    /** 일자별 환불 완료 건수·금액. 환불이 없던 날은 행이 없다. */
    List<DailyAmount> refundsByDay(LocalDateTime from, LocalDateTime toExclusive);

    /**
     * 같은 기간에 수납된 결제의 결제수단별 구성.
     *
     * <p>수단 행이 하나도 없는 결제(분할결제 도입 전 건)는 여기에 잡히지 않는다. 그 차이는
     * 서비스가 총액과 대조해 "수단 미상"으로 드러낸다.
     */
    List<TenderRevenue> capturedByTender(LocalDateTime from, LocalDateTime toExclusive);

    /** 하루치 집계 한 줄. */
    record DailyAmount(LocalDate date, long count, BigDecimal amount) {
    }
}
