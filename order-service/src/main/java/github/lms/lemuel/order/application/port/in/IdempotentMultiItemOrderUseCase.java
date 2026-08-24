package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

import java.util.List;

/**
 * Idempotency-Key 기반 중복 방지 다건 주문 생성 UseCase (Inbound Port).
 *
 * <p>{@link CreateMultiItemOrderUseCase} 를 감싸 동일 키 중복 제출을 분산 락 + DB UNIQUE 로 차단한다.
 */
public interface IdempotentMultiItemOrderUseCase {

    /**
     * @param idempotencyKey 멱등 키. null/빈 문자열이면 일반 생성(하위 호환).
     */
    Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                 String couponCode, String idempotencyKey);
}
