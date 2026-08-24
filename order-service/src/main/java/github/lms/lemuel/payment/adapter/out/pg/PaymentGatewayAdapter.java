package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;

import java.math.BigDecimal;

/**
 * 단일 PG 어댑터의 공통 컨트랙트.
 *
 * <p>{@link github.lms.lemuel.payment.application.port.out.PgClientPort} 가
 * 헥사고날 아웃바운드 포트 (도메인 계약) 라면, 이 인터페이스는 그 아래에서
 * "여러 PG 어댑터를 라우팅하기 위한 내부 SPI" 역할을 한다.
 *
 * <p>구현체는 {@link PaymentGateway} 별로 1 개씩 존재하고
 * {@link PgRouter} 가 결제 수단 / 거래 금액 / health 를 보고 적절한 어댑터를 고른다.
 *
 * <p>실 운영에서는 각 어댑터에 PG 별 Resilience4j CircuitBreaker 인스턴스를 붙여
 * 한 PG 의 장애가 다른 PG 로 전이되지 않도록 격리한다 (예: {@code @CircuitBreaker(name="kcpPg")}).
 */
public interface PaymentGatewayAdapter {

    /**
     * 이 어댑터가 어떤 PG 를 다루는지.
     */
    PaymentGateway provider();

    /**
     * 주어진 결제 수단을 이 PG 가 처리할 수 있는지 (라우팅 1차 필터).
     * 예: KAKAO_PAY 는 일부 PG 만 지원한다.
     */
    boolean supports(String paymentMethod);

    /**
     * PG 와 통신 가능한 상태인지 (CircuitBreaker OPEN 시 false).
     * 라우터는 healthy 한 어댑터만 후보로 고른다.
     */
    boolean isHealthy();

    /**
     * 결제 승인 → PG 거래 ID 반환.
     * 반환 값에는 반드시 {@link PaymentGateway#prefix()} prefix 를 붙여
     * 후속 capture / refund 가 동일 PG 로 라우팅되도록 한다.
     */
    String authorize(Long paymentId, BigDecimal amount, String paymentMethod);

    /**
     * 승인된 거래 매입 — pgTransactionId 의 prefix 가 자기 자신이어야 한다.
     */
    void capture(String pgTransactionId, BigDecimal amount);

    /**
     * 매입된 거래 환불 (부분/전체) — 동일 PG 로 라우팅된다.
     *
     * @param idempotencyKey PG 에 전달할 멱등 키(예: Toss 의 {@code Idempotency-Key} 헤더). 같은 키
     *                       재요청은 PG 가 중복 환불하지 않는다. 자동 재시도의 이중 환불 방지에 쓰인다.
     */
    void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey);
}
