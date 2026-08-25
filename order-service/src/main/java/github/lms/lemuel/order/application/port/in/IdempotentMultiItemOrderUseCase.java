package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;

import java.util.List;

/**
 * Idempotency-Key 기반 중복 방지 다건 주문 생성 UseCase (Inbound Port).
 *
 * <p>{@link CreateMultiItemOrderUseCase} 를 감싸 동일 키 중복 제출을 분산 락 + DB UNIQUE 로 차단한다.
 */
public interface IdempotentMultiItemOrderUseCase {

    /**
     * @param shippingAddress 주문 시점 배송지. 주어지면 배송(PENDING)까지 같은 트랜잭션에서 생성된다.
     * @param idempotencyKey  멱등 키. null/빈 문자열이면 일반 생성(하위 호환).
     */
    Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                 String couponCode, ShippingAddressSnapshot shippingAddress, String idempotencyKey);

    /** 배송지 없이 만드는 멱등 주문 (기존 호출 호환). */
    default Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                         String couponCode, String idempotencyKey) {
        return create(userId, lines, couponCode, null, idempotencyKey);
    }
}
