package github.lms.lemuel.order.domain;

import java.math.BigDecimal;

/**
 * 조회 조건 전체의 판매 합계.
 *
 * <p><b>왜 따로 세는가</b>: 랭킹은 상위 N개만 돌려준다. 화면이 그 N개를 더해 "총 판매액"으로
 * 쓰면 <b>상위 20개의 합이 전체 매출로 둔갑한다</b> — 숫자가 그럴듯해서 틀렸다는 신호가 없고,
 * 상품이 늘수록 조용히 더 틀린다. 그래서 합계는 잘라내기 전의 전 범위에서 따로 센다.
 *
 * <p>카테고리별 조회에서는 잘라내기가 없으므로 이 값이 각 줄의 합과 <b>정확히 같아야</b> 한다.
 * 같지 않다면 대표 카테고리가 없는 상품이 어디선가 빠졌거나 한 라인이 두 번 세어진 것이다.
 *
 * @param quantity   판매 수량 합(취소된 라인 제외)
 * @param netAmount  라인 순액 합 = Σ(line_amount - allocated_discount)
 * @param lineCount  집계에 들어간 라인 수
 * @param orderCount 집계에 들어간 주문 수(라인이 여럿이어도 한 번)
 */
public record SalesTotal(
        long quantity,
        BigDecimal netAmount,
        long lineCount,
        long orderCount
) {

    public static SalesTotal empty() {
        return new SalesTotal(0L, BigDecimal.ZERO, 0L, 0L);
    }
}
