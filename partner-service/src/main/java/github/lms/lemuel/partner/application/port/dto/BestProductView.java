package github.lms.lemuel.partner.application.port.dto;

import java.math.BigDecimal;

/**
 * 많이 팔린 상품 한 줄.
 *
 * <p>{@code productId} 가 null 인 행이 나올 수 있다 — 결제는 왔는데 그 주문의
 * {@code order.created} 가 아직 안 왔거나(토픽이 달라 순서 보장 없음), 주문에 상품이 없는
 * 경우다. 그 행을 버리지 않는 이유는 버리면 상품별 합이 총매출과 안 맞아서다. 화면은
 * "미확인 상품" 으로 표기한다.
 *
 * <p>{@code productName} 이 null 인 것도 정상이다 — 계약상 {@code product.changed.name} 은
 * nullable 이고, 아직 그 이벤트를 못 받았을 수도 있다. 이때는 상품 ID 로 대체 표기한다.
 *
 * <p>금액이 <b>환불을 뺀 실매출</b>인 것은 의도다. 총매출로 줄을 세우면 전량 환불된 상품이
 * 베스트 1위에 앉는다 — 레퍼런스 백오피스가 실제로 그랬다. 순위와 금액이 서로 다른 기준을
 * 쓰면 화면에서 그 사실이 보이지 않으므로, 둘 다 실매출로 통일한다.
 */
public record BestProductView(
        Long productId,
        String productName,
        BigDecimal netAmount,
        long orderCount) {
}
