package github.lms.lemuel.order.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * order 도메인 이벤트 발행 아웃바운드 포트 (Transactional Outbox 경유).
 *
 * <p>주문 생성을 이벤트로 발행해 settlement-service 등이 로컬 주문 프로젝션(order_view)을
 * 동기화하게 한다 (ADR 0020 Phase 3b, Event-Carried State Transfer).
 */
public interface PublishOrderEventPort {

    void publishOrderCreated(Long orderId, Long userId, Long productId,
                             String status, BigDecimal amount, LocalDateTime createdAt);
}
