package github.lms.lemuel.order.application.port.out;

import java.util.Optional;

/**
 * 주문 멱등 저장소 아웃바운드 포트 — Idempotency-Key 로 기존 주문을 식별/기록한다.
 */
public interface OrderIdempotencyPort {

    /** 키에 연결된 주문 id (이미 처리된 요청이면 존재). */
    Optional<Long> findOrderId(String idempotencyKey);

    /**
     * 키→주문 매핑 기록. 동일 키가 이미 있으면 제약 위반 예외를 던져(같은 트랜잭션의 주문까지 롤백)
     * 중복 주문을 차단한다.
     */
    void save(String idempotencyKey, Long orderId);
}
