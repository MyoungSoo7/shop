package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.PgClientPort;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 단순 mock PG 어댑터.
 *
 * <p>Phase 1 다중 PG 라우팅 도입 이후로는 Spring 빈으로 등록되지 않는다 —
 * 라우팅은 {@link RoutingPgClientAdapter} 가 담당하고, 각 PG 별 mock 동작은
 * {@code adapter.out.pg} 패키지의 {@code TossPgAdapter} / {@code KcpPgAdapter} 등이 갖는다.
 *
 * <p>이 클래스는 단위 테스트에서 PgClientPort 가 필요할 때 가벼운 fallback 으로 직접
 * {@code new MockPgClientAdapter()} 형태로 인스턴스화하는 용도로만 남겨둔다.
 */
public class MockPgClientAdapter implements PgClientPort {

    @Override
    public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
        return "MOCK:" + UUID.randomUUID();
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        // no-op
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) {
        // no-op
    }
}
