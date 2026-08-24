package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.adapter.out.pg.PaymentGatewayAdapter;
import github.lms.lemuel.payment.adapter.out.pg.PgRouter;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link PgClientPort} 의 라우팅 구현체.
 *
 * <p>도메인 / 애플리케이션 계층은 {@code PgClientPort} 단일 인터페이스만 보지만,
 * 그 아래에서는 {@link PgRouter} 가 4 개 PG 어댑터 (TOSS / KCP / NICE / INICIS) 중 하나를 선택한다.
 *
 * <p>이렇게 하면:
 * <ul>
 *   <li>도메인은 PG 가 무엇인지 알 필요 없음 — 헥사고날 경계 보존</li>
 *   <li>capture / refund 는 거래 ID prefix 로 동일 PG 로 정확히 라우팅됨 — DB 변경 0</li>
 *   <li>한 PG 의 장애가 다른 PG 로 전이되지 않음 — 격벽 (bulkhead) + 서킷</li>
 * </ul>
 */
@Primary
@Component
public class RoutingPgClientAdapter implements PgClientPort {

    private static final Logger log = LoggerFactory.getLogger(RoutingPgClientAdapter.class);

    private final PgRouter router;

    public RoutingPgClientAdapter(PgRouter router) {
        this.router = router;
    }

    @Override
    public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
        PaymentGatewayAdapter adapter = router.selectFor(amount, paymentMethod);
        log.info("PG 라우팅: paymentId={}, amount={}, method={} → {}",
                paymentId, amount, paymentMethod, adapter.provider());
        return adapter.authorize(paymentId, amount, paymentMethod);
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        PaymentGatewayAdapter adapter = router.resolveByTransactionId(pgTransactionId);
        adapter.capture(pgTransactionId, amount);
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount, String idempotencyKey) {
        PaymentGatewayAdapter adapter = router.resolveByTransactionId(pgTransactionId);
        adapter.refund(pgTransactionId, amount, idempotencyKey);
    }
}
