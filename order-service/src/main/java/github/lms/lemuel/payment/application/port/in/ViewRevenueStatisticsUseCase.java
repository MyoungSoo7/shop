package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.TenderType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 기간 매출 통계 — 일자별 추이와 결제수단별 구성.
 *
 * <h2>왜 새로 만드나 — 지금 화면의 "총 매출"은 틀린다</h2>
 * 관리자 대시보드의 총 매출은 {@code /orders/admin/summary} 가 준 상태별 합계 중 <b>현재 상태가
 * PAID 인 주문</b>의 주문금액 합이다. 주문이 발송(IN_TRANSIT)되거나 배송 완료(DELIVERED)되면
 * 그 주문은 PAID 가 아니게 되므로 매출에서 <b>빠진다</b> — 장사가 잘 굴러갈수록 매출이 줄어드는
 * 숫자다. 환불도 차감되는 게 아니라 다른 상태 칸으로 옮겨갈 뿐이라 그냥 사라진다.
 *
 * <p>가장 나쁜 점은 그 숫자가 언제나 <b>그럴듯한 값</b>이라는 것이다. 0이 되거나 오류가 나면
 * 누군가 알아채겠지만, 실제보다 작은 매출은 아무도 이상하게 여기지 않는다.
 *
 * <h2>여기서 정하는 매출의 정의</h2>
 * <ul>
 *   <li><b>총 수납액</b> — 실제로 캡처된 결제 금액. 시간축은 주문의 현재 상태가 아니라
 *       {@code payments.captured_at} 이다. 이미 일어난 수납은 주문이 그 뒤에 어떻게 되든
 *       그 날짜에 그대로 남는다.
 *   <li><b>환불액</b> — 완료된 환불({@code refunds.completed_at}) 기준.
 *   <li><b>순매출</b> = 총 수납액 − 환불액.
 * </ul>
 *
 * <p>환불을 <b>환불이 일어난 날</b>에 다는 것은 선택이다. 원래 판매일로 소급해 다는 방식도 회계상
 * 성립하지만, 그러면 이미 보고된 지난달 숫자가 오늘 조용히 바뀐다. 닫힌 기간은 닫힌 채로 두고,
 * 환불은 환불이 난 날의 사실로 기록한다. 그래서 하루치 순매출은 <b>음수가 될 수 있다</b> —
 * 판매가 없고 환불만 있는 날은 실제로 그렇다.
 */
public interface ViewRevenueStatisticsUseCase {

    /** 기간 매출 보고서. */
    RevenueReport report(RevenueQuery query);

    /**
     * 조회 기간 — 날짜 단위 폐구간.
     *
     * @param from        시작일(포함)
     * @param toInclusive 종료일(포함). 화면이 "8월 1일 ~ 8월 31일"이라고 말할 때 31일이 빠지면
     *                    아무도 눈치채지 못한 채 하루가 사라지므로, 경계 해석은 여기서 한 번만
     *                    정하고 어댑터는 반개구간을 그대로 받는다
     */
    record RevenueQuery(LocalDate from, LocalDate toInclusive) {

        /** 조회 상한 — 일자 행을 그대로 응답에 싣기 때문에 무한정 열어 두지 않는다. */
        public static final int MAX_DAYS = 366;

        public RevenueQuery {
            if (from == null || toInclusive == null) {
                throw new IllegalArgumentException("조회 기간은 필수입니다");
            }
            if (toInclusive.isBefore(from)) {
                throw new IllegalArgumentException("종료일이 시작일보다 앞설 수 없습니다");
            }
            if (from.plusDays(MAX_DAYS - 1L).isBefore(toInclusive)) {
                throw new IllegalArgumentException("조회 기간은 최대 " + MAX_DAYS + "일입니다");
            }
        }
    }

    /**
     * 기간 매출 보고서.
     *
     * @param daily              일자별 추이. <b>수납도 환불도 없던 날은 행이 없다</b> — 0 을 채워
     *                           넣는 것은 화면의 몫이고, 서버가 채우면 "집계가 안 돌았다"와
     *                           "그날 장사가 없었다"를 구분할 수 없게 된다
     * @param byTender           결제수단별 구성
     * @param capturedAmount     기간 총 수납액
     * @param refundedAmount     기간 총 환불액
     * @param unattributedAmount 수단을 특정하지 못한 수납액. {@link #byTender} 참고
     */
    record RevenueReport(
            List<DailyRevenue> daily,
            List<TenderRevenue> byTender,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            BigDecimal unattributedAmount) {

        /** 순매출 = 총 수납액 − 환불액. 환불만 있는 기간이면 음수다. */
        public BigDecimal netAmount() {
            return capturedAmount.subtract(refundedAmount);
        }

        /**
         * 결제수단별 합계가 총 수납액을 전부 설명하는가.
         *
         * <p>{@code false} 면 화면은 반드시 그 사실을 말해야 한다. 구성 비율만 그려 놓으면
         * 합이 총액에 못 미치는 것을 보는 사람이 없다.
         */
        public boolean tenderBreakdownIsComplete() {
            return unattributedAmount.signum() == 0;
        }
    }

    /**
     * 하루치.
     *
     * @param capturedCount 수납 건수
     * @param refundCount   환불 건수
     */
    record DailyRevenue(
            LocalDate date,
            long capturedCount,
            BigDecimal capturedAmount,
            long refundCount,
            BigDecimal refundedAmount) {

        public BigDecimal netAmount() {
            return capturedAmount.subtract(refundedAmount);
        }
    }

    /**
     * 결제수단 한 칸.
     *
     * <p>{@code payment_tenders} 에서 나온다 — 분할결제가 있으므로 결제 한 건이 여러 수단에
     * 걸친다. {@code payments.payment_method} 한 칸으로 세면 5만원짜리 결제가 포인트 5천 +
     * 카드 4만5천이어도 통째로 한 수단에 붙는다.
     *
     * @param usesExternalPg 외부 PG 로 실제 돈이 들어왔는가. POINT·GIFT_CARD 는 내부 잔액
     *                       차감이라 이 기간에 새로 들어온 현금이 아니다 — 상품권은 팔릴 때
     *                       이미 한 번 수납됐다. 한 줄에 섞어 합치면 그만큼 이중으로 세므로
     *                       축을 남겨 화면이 갈라 볼 수 있게 한다
     */
    record TenderRevenue(
            TenderType tenderType,
            boolean usesExternalPg,
            long count,
            BigDecimal amount) {
    }
}
