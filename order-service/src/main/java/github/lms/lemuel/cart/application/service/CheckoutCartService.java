package github.lms.lemuel.cart.application.service;

import github.lms.lemuel.cart.application.port.in.CheckoutCartUseCase;
import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 장바구니 체크아웃 — Cart → 다건 주문 (Order) 변환.
 *
 * <p>같은 트랜잭션 안에서:
 * <ol>
 *   <li>장바구니 항목을 다건 주문 line 으로 변환</li>
 *   <li>{@link CreateMultiItemOrderUseCase#create} 호출 → 재고 차감 + Order 생성</li>
 *   <li>주문 생성 성공 시 장바구니 비우기</li>
 * </ol>
 *
 * <p>주문 생성 실패 (재고 부족, PG 오류 등) 시 트랜잭션 롤백 → 장바구니 유지 → 사용자 재시도 가능.
 */
@Service
@Transactional
public class CheckoutCartService implements CheckoutCartUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckoutCartService.class);

    private final LoadCartPort loadCartPort;
    private final SaveCartPort saveCartPort;
    private final CreateMultiItemOrderUseCase createOrderUseCase;

    public CheckoutCartService(LoadCartPort loadCartPort,
                                SaveCartPort saveCartPort,
                                CreateMultiItemOrderUseCase createOrderUseCase) {
        this.loadCartPort = loadCartPort;
        this.saveCartPort = saveCartPort;
        this.createOrderUseCase = createOrderUseCase;
    }

    @Override
    public Order checkout(Long userId) {
        Cart cart = loadCartPort.loadByUserId(userId)
                .orElseThrow(() -> new CartInvariantViolationException("장바구니가 없습니다"));
        if (cart.isEmpty()) {
            throw new CartInvariantViolationException("장바구니가 비어있습니다");
        }

        List<CreateMultiItemOrderUseCase.Line> lines = cart.getItems().stream()
                .map(this::toOrderLine)
                .toList();

        Order order = createOrderUseCase.create(userId, lines);
        log.info("[Checkout] cart={} → order={}, items={}",
                cart.getId(), order.getId(), lines.size());

        // 주문 생성 성공 → 장바구니 비우기 (실패하면 위에서 예외로 롤백되어 도달 X)
        cart.clear();
        saveCartPort.save(cart);
        return order;
    }

    private CreateMultiItemOrderUseCase.Line toOrderLine(CartItem item) {
        return new CreateMultiItemOrderUseCase.Line(
                item.getProductId(), item.getVariantId(), item.getQuantity());
    }
}
