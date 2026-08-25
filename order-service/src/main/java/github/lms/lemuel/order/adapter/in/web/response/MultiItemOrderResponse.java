package github.lms.lemuel.order.adapter.in.web.response;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 다건 주문 생성 응답.
 *
 * <p>{@link OrderResponse} 와 달리 <b>라인과 금액 구성</b>을 함께 돌려준다. 이유는 화면 때문이다 —
 * 장바구니 결제 완료 화면은 상품별 결과와 "얼마가 어떻게 깎여 이 금액이 됐는지"를 보여준다.
 * 이걸 응답에 싣지 않으면 클라이언트가 단가를 다시 계산해 화면을 그리게 되고, 그 순간 서버가
 * 확정한 금액과 화면의 금액이 갈라진다(그게 원래 이 경로를 안 쓰던 시절의 문제였다).
 *
 * <p>{@code subtotal}·{@code discountAmount} 는 저장된 라인에서 합산한 값이다. 주문 금액은
 * {@code subtotal - discountAmount + shippingFee} 이며 {@link Order} 가 계산해 못박는다.
 */
public record MultiItemOrderResponse(
        Long id,
        Long userId,
        BigDecimal amount,
        String status,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        LocalDateTime createdAt,
        ShippingAddress shippingAddress,
        List<Line> items) {

    /**
     * 주문 시점에 굳은 배송지. 배송지 없이 만들어진 <b>과거 주문은 null</b> 이다 —
     * 이 필드가 생기기 전 주문은 주소를 아예 갖고 있지 않다.
     */
    public record ShippingAddress(
            String recipientName,
            String phone,
            String postalCode,
            String address1,
            String address2,
            String deliveryMemo) {

        static ShippingAddress from(ShippingAddressSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            return new ShippingAddress(snapshot.recipientName(), snapshot.phone(),
                    snapshot.postalCode(), snapshot.address1(), snapshot.address2(),
                    snapshot.deliveryMemo());
        }
    }

    /**
     * 주문 라인 한 줄. {@code allocatedDiscount} 는 이 라인이 짊어진 할인 몫으로, 부분 취소가
     * 환불하는 단위({@code netAmount})가 여기서 나온다.
     */
    public record Line(
            Long id,
            Long productId,
            Long variantId,
            String sku,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineAmount,
            BigDecimal allocatedDiscount,
            BigDecimal netAmount) {

        static Line from(OrderItem item) {
            return new Line(item.getId(), item.getProductId(), item.getVariantId(), item.getSku(),
                    item.getProductName(), item.getUnitPrice(), item.getQuantity(),
                    item.getLineAmount(), item.getAllocatedDiscount(), item.getNetAmount());
        }
    }

    public static MultiItemOrderResponse from(Order order) {
        BigDecimal subtotal = order.getItems().stream()
                .map(OrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = order.getItems().stream()
                .map(OrderItem::getAllocatedDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MultiItemOrderResponse(
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getStatus().name(),
                subtotal,
                discount,
                order.getShippingFee(),
                order.getCreatedAt(),
                ShippingAddress.from(order.getShippingAddress()),
                order.getItems().stream().map(Line::from).toList());
    }
}
