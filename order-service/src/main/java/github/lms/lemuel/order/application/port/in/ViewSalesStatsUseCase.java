package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.ProductSales;
import github.lms.lemuel.order.domain.SalesTotal;

import java.time.LocalDate;
import java.util.List;

/**
 * 판매 통계 조회 — 상품 랭킹과 카테고리별 분포.
 *
 * <p><b>주문 요약({@code /orders/admin/summary})과 무엇이 다른가</b>: 그쪽은 <b>주문</b>을 상태별로
 * 세고 {@code orders.amount} 를 더한다. 그래서 "무엇이 팔렸는가"에는 답하지 못한다 — 주문 한 건에
 * 상품 다섯 개가 들어 있어도 그 다섯을 구분할 축이 없다. 여기서 세는 단위는 <b>주문 라인</b>이다.
 *
 * <p>두 숫자는 일부러 다르다. 주문 총액은 배송비를 포함하고 라인 순액은 포함하지 않으므로,
 * 같아지도록 맞추려 하면 안 된다. 무엇을 세고 있는지가 응답에 함께 실린다.
 */
public interface ViewSalesStatsUseCase {

    /** 상위 상품 랭킹. */
    ProductRanking topProducts(SalesQuery query);

    /** 카테고리별 분포. 잘라내지 않고 전부 돌려준다(미분류 포함). */
    CategoryBreakdown byCategory(SalesQuery query);

    /**
     * 조회 조건.
     *
     * @param from     시작일(포함). {@code null} 이면 기본 기간.
     * @param to       종료일(포함). {@code null} 이면 오늘(KST).
     * @param statuses 셀 주문 상태. {@code null}·빈 목록이면 기본 집합(결제가 살아 있는 상태).
     *                 <b>"전부"가 아니다</b> — 기본을 전체로 두면 결제도 안 된 주문과 이미
     *                 환불한 주문이 판매 실적에 들어간다.
     * @param limit    랭킹 행 수. {@code null} 이면 기본값. 카테고리 조회는 무시한다.
     */
    record SalesQuery(LocalDate from, LocalDate to, List<String> statuses, Integer limit) {
    }

    /**
     * 상품 랭킹 결과.
     *
     * @param rows  상위 {@code limit} 개
     * @param total <b>잘라내기 전</b> 전 범위 합계. {@code rows} 를 더한 값이 아니다 —
     *              화면은 이 값으로 "상위 N개가 전체의 몇 %"를 말할 수 있다.
     */
    record ProductRanking(
            LocalDate from,
            LocalDate to,
            List<String> statuses,
            int limit,
            List<ProductSales> rows,
            SalesTotal total) {
    }

    /**
     * 카테고리별 결과.
     *
     * <p>여기서는 잘라내기가 없으므로 {@code rows} 의 순액 합이 {@code total.netAmount()} 와
     * 정확히 같아야 한다. 어긋난다면 대표 분류가 없는 상품이 빠졌거나 한 라인이 여러 분류로
     * 중복 계산된 것이다.
     */
    record CategoryBreakdown(
            LocalDate from,
            LocalDate to,
            List<String> statuses,
            List<CategorySales> rows,
            SalesTotal total) {
    }
}
