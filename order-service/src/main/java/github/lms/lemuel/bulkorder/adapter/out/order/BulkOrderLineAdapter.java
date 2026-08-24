package github.lms.lemuel.bulkorder.adapter.out.order;

import github.lms.lemuel.bulkorder.application.port.out.PlaceBulkOrderLinePort;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 대량주문 행 → 실주문 + 배송지 연결 어댑터.
 *
 * <p>주문 생성·재고 차감·금액 확정은 {@link CreateMultiItemOrderUseCase} 가, 배송지 등록은
 * {@link ShippingUseCase} 가 이미 규칙을 갖고 있다. 여기서 다시 짜면 같은 규칙이 두 벌이 되어
 * 한쪽만 고쳐지는 날이 온다 — 대량주문은 "값을 어디서 가져왔는가"만 다를 뿐 주문은 주문이다.
 *
 * <p>수량은 라인 1개로 넘긴다. 대량주문 양식 한 행 = 상품 1종이므로 다품목 라인이 필요 없다.
 */
@Component
public class BulkOrderLineAdapter implements PlaceBulkOrderLinePort {

    private final CreateMultiItemOrderUseCase createMultiItemOrderUseCase;
    private final ShippingUseCase shippingUseCase;

    public BulkOrderLineAdapter(CreateMultiItemOrderUseCase createMultiItemOrderUseCase,
                                ShippingUseCase shippingUseCase) {
        this.createMultiItemOrderUseCase = createMultiItemOrderUseCase;
        this.shippingUseCase = shippingUseCase;
    }

    @Override
    public Long place(Long buyerUserId, Line line) {
        Order order = createMultiItemOrderUseCase.create(buyerUserId, List.of(
                new CreateMultiItemOrderUseCase.Line(line.productId(), null, line.quantity())));

        // 배송지는 주문과 같은 (행) 트랜잭션 안에서 붙는다 — 배송지 없는 주문이 남으면
        // 운영자가 그 주문만 따로 찾아 손으로 채워야 한다.
        shippingUseCase.createForOrder(order.getId(), new ShippingAddress(
                line.recipientName(), line.phone(), line.postalCode(),
                line.address1(), blankToNull(line.address2()), blankToNull(line.memo())));

        return order.getId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
