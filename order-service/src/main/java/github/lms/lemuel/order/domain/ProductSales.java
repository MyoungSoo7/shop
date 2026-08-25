package github.lms.lemuel.order.domain;

import java.math.BigDecimal;

/**
 * 상품 한 줄의 판매 실적.
 *
 * @param productId   상품 ID. 상품이 삭제됐어도 주문 라인은 남으므로 이 값은 항상 있다.
 * @param productName 주문 시점 <b>스냅샷</b> 이름 중 기간 안에서 가장 최근 것. 지금의 상품명이
 *                    아니다 — 이름이 바뀐 상품은 옛 주문에 옛 이름으로 적혀 있고, 그 둘을
 *                    합쳐야 "이 상품이 얼마나 팔렸는가"에 답할 수 있다(그래서 묶는 축은 이름이
 *                    아니라 {@code productId} 다).
 * @param quantity    수량 합(취소된 라인 제외)
 * @param netAmount   라인 순액 합 = Σ(line_amount - allocated_discount).
 *                    {@code line_amount} 만 더하면 쿠폰 할인분이 매출로 잡혀 <b>실제보다 크게</b>
 *                    나온다 — 할인율이 높은 상품일수록 더 크게 틀린다.
 * @param orderCount  이 상품이 팔린 주문 수(한 주문에 여러 라인이어도 1)
 */
public record ProductSales(
        Long productId,
        String productName,
        long quantity,
        BigDecimal netAmount,
        long orderCount
) {
}
