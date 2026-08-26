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
     * @param consent         주문 시점 동의. {@code null} 이면 동의를 받지 않는 경로다
     * @param idempotencyKey  멱등 키. null/빈 문자열이면 일반 생성(하위 호환).
     */
    Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                 String couponCode, ShippingAddressSnapshot shippingAddress,
                 CreateMultiItemOrderUseCase.ConsentSubmission consent, String idempotencyKey);

    /**
     * 동의를 받지 않는 경로 (기존 호출 호환).
     *
     * <p>재시도로 같은 키가 다시 와도 동의가 두 번 쌓이지 않는다 — 멱등 replay 는 주문 생성 자체를
     * 건너뛰므로 동의 기록에도 닿지 않는다. 그 위에 DB 의 {@code ux_order_privacy_consents_order_code}
     * 가 한 겹 더 서 있다.
     */
    default Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                         String couponCode, ShippingAddressSnapshot shippingAddress,
                         String idempotencyKey) {
        return create(userId, lines, couponCode, shippingAddress, null, idempotencyKey);
    }

    /** 배송지 없이 만드는 멱등 주문 (기존 호출 호환). */
    default Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                         String couponCode, String idempotencyKey) {
        return create(userId, lines, couponCode, null, null, idempotencyKey);
    }
}
