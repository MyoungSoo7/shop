package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.CategorySales;
import github.lms.lemuel.order.domain.ProductSales;
import github.lms.lemuel.order.domain.SalesTotal;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 라인 기반 판매 집계 조회.
 *
 * <p>세 질문이 <b>같은 {@link SalesCriteria} 를 공유한다</b>. 조건을 따로 받으면 랭킹과 합계가
 * 다른 모집단을 세게 되는데, 그때 나오는 "상위 20개가 전체의 130%" 같은 화면은 어느 쪽이
 * 틀렸는지 알려 주지 않는다.
 */
public interface LoadSalesStatsPort {

    /** 순액 내림차순 상위 {@code limit} 개. */
    List<ProductSales> topProducts(SalesCriteria criteria, int limit);

    /** 대표 분류 기준 전체 분포. 대표 분류가 없는 상품은 {@code categoryId == null} 한 줄로 모인다. */
    List<CategorySales> byCategory(SalesCriteria criteria);

    /** 잘라내기 없는 전 범위 합계. */
    SalesTotal total(SalesCriteria criteria);

    /**
     * 집계 대상 조건.
     *
     * <p>기간은 <b>반열림</b>이다({@code createdFrom <= created_at < createdToExclusive}).
     * 양끝 포함으로 다루면 종료일의 마지막 1초를 어떻게 표현할지가 호출자마다 갈리고,
     * {@code 23:59:59} 로 적는 순간 그날 마지막 1초의 주문이 조용히 빠진다.
     * {@code orders.created_at} 은 타임존 없는 컬럼이고 애플리케이션이 KST 벽시계를 그대로
     * 넣는다 — 여기서 다시 변환하지 않는 이유다({@code OrderPersistenceAdapter} 와 같은 규약).
     *
     * @param statuses 셀 주문 상태. 비어 있으면 <b>절대 안 된다</b> — 서비스가 기본 집합을 채운다.
     */
    record SalesCriteria(
            List<String> statuses,
            LocalDateTime createdFrom,
            LocalDateTime createdToExclusive) {
    }
}
