package github.lms.lemuel.bulkorder.adapter.out.order;

import github.lms.lemuel.bulkorder.application.port.out.PlaceBulkOrderLinePort;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 대량주문 행 → 실주문 + 배송지 연결 어댑터.
 *
 * <p>주문 생성·재고 차감·금액 확정·배송지 확정은 {@link CreateMultiItemOrderUseCase} 가 이미
 * 규칙을 갖고 있다. 여기서 다시 짜면 같은 규칙이 두 벌이 되어 한쪽만 고쳐지는 날이 온다 —
 * 대량주문은 "값을 어디서 가져왔는가"만 다를 뿐 주문은 주문이다.
 *
 * <p>배송지를 주문 생성 인자로 넘기는 이유: 예전엔 주문을 만든 뒤 배송지를 따로 붙였는데,
 * 그러면 두 호출 사이에 실패한 주문이 <b>배송지 없는 주문</b>으로 남아 운영자가 손으로 채워야 했다.
 * 지금은 주문 행에 스냅샷으로 같이 INSERT 되고 배송(PENDING)도 같은 트랜잭션에서 만들어진다.
 *
 * <p>수량은 라인 1개로 넘긴다. 대량주문 양식 한 행 = 상품 1종이므로 다품목 라인이 필요 없다.
 */
@Component
public class BulkOrderLineAdapter implements PlaceBulkOrderLinePort {

    private final CreateMultiItemOrderUseCase createMultiItemOrderUseCase;

    public BulkOrderLineAdapter(CreateMultiItemOrderUseCase createMultiItemOrderUseCase) {
        this.createMultiItemOrderUseCase = createMultiItemOrderUseCase;
    }

    @Override
    public Long place(Long buyerUserId, Line line) {
        Order order = createMultiItemOrderUseCase.create(buyerUserId,
                List.of(new CreateMultiItemOrderUseCase.Line(line.productId(), null, line.quantity())),
                null,
                new ShippingAddressSnapshot(line.recipientName(), line.phone(), line.postalCode(),
                        line.address1(), line.address2(), line.memo()));

        return order.getId();
    }
}
