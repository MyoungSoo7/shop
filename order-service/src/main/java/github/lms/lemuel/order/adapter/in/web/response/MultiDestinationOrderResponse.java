package github.lms.lemuel.order.adapter.in.web.response;

import github.lms.lemuel.order.domain.Order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 여러 곳 배송 응답 — 한 번의 결제로 만들어진 주문들.
 *
 * <p>주문 목록을 그대로 싣는다. 배송지마다 주문이 하나씩이므로 각 원소가 곧 한 배송지의 결과이고,
 * 배송비도 금액도 그 주문 안에 이미 들어 있다.
 *
 * <p>{@code totalAmount} 는 그 주문들의 합이다. 응답에 넣는 이유는 화면이 다시 더하지 않게 하려는
 * 것이다 — 결제 완료 화면이 스스로 합산하면 서버가 확정한 금액과 화면의 금액이 갈라질 수 있는
 * 자리가 하나 더 생긴다. 원본(ssg-front)의 같은 기능이 실제로 그 자리에서 틀렸다: 장바구니 총액을
 * 배송지 수만큼 곱해 청구하면서 재고는 한 벌만 뺐다.
 */
public record MultiDestinationOrderResponse(
        String destinationGroupId,
        BigDecimal totalAmount,
        List<MultiItemOrderResponse> orders) {

    public static MultiDestinationOrderResponse from(String destinationGroupId, List<Order> orders) {
        BigDecimal total = orders.stream()
                .map(Order::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MultiDestinationOrderResponse(
                destinationGroupId,
                total,
                orders.stream().map(MultiItemOrderResponse::from).toList());
    }
}
