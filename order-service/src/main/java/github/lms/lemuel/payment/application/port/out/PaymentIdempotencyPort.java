package github.lms.lemuel.payment.application.port.out;

import java.util.Optional;

/**
 * 결제 승인 멱등 저장소 아웃바운드 포트 — {@code Idempotency-Key → payment_id} 매핑.
 *
 * <p>주문 생성의 {@code OrderIdempotencyPort} 와 같은 패턴이다. 다른 점은 <b>키의 출처</b>다:
 * 주문 생성은 클라이언트가 키를 만들지만, PG 승인은 결제창이 발급한 {@code paymentKey} 자체가
 * 그 승인 시도를 유일하게 가리키므로, 헤더가 없으면 {@code paymentKey} 를 키로 삼는다. 덕분에
 * 헤더를 보내지 않는 기존 클라이언트의 재시도도 이중 승인 없이 replay 된다.
 */
public interface PaymentIdempotencyPort {

    /** 이미 처리된 키면 그때 만든 결제 ID. 처음 보는 키면 {@link Optional#empty()}. */
    Optional<Long> findPaymentId(String idempotencyKey);

    /**
     * 키↔결제 매핑을 기록한다. 동일 키가 이미 있으면 제약 위반으로 실패해야 한다
     * (같은 트랜잭션의 결제 생성까지 롤백되어 최종 1건이 보장된다).
     */
    void save(String idempotencyKey, Long paymentId);
}
