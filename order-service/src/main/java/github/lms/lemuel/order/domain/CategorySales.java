package github.lms.lemuel.order.domain;

import java.math.BigDecimal;

/**
 * 카테고리 한 줄의 판매 실적.
 *
 * <p><b>대표 분류 하나로만 센다</b>. 상품과 카테고리는 M:N 이라 한 상품이 세 분류에 걸려 있으면
 * 단순 조인은 그 라인을 <b>세 번</b> 센다. 카테고리별 합이 전체 매출보다 커지는데, 각 줄만 보면
 * 멀쩡해서 아무도 눈치채지 못한다. {@code product_ecommerce_categories.is_primary}
 * (상품당 최대 1행을 부분 유니크 인덱스가 강제한다)만 쓰면 그 곱셈이 원천에서 사라진다.
 *
 * @param categoryId   {@code null} 이면 <b>미분류</b> — 어떤 분류에도 붙지 않은 상품의 몫이다.
 *                     이 줄을 빼면 카테고리 합계가 전체 매출보다 작아지고, 그 차이는 화면에
 *                     나타나지 않는다. 팔리는데 분류가 없는 상품이야말로 운영이 먼저 알아야 할
 *                     대상이라 0 원이 아닌 이상 감추지 않는다.
 * @param categoryName 분류명. 미분류 줄은 {@code null} 이며 표기는 화면이 아니라 서버가 정한다.
 * @param pathSlug     루트→자기 경로 slug. 같은 이름의 하위 분류가 여럿일 때 어느 가지인지 구분한다.
 * @param depth        0=대분류, 1=중분류, 2=소분류. 미분류는 {@code null}.
 * @param quantity     수량 합(취소된 라인 제외)
 * @param netAmount    라인 순액 합 = Σ(line_amount - allocated_discount)
 * @param orderCount   이 분류의 상품이 팔린 주문 수
 */
public record CategorySales(
        Long categoryId,
        String categoryName,
        String pathSlug,
        Integer depth,
        long quantity,
        BigDecimal netAmount,
        long orderCount
) {

    /** 미분류 줄인가. */
    public boolean unclassified() {
        return categoryId == null;
    }
}
