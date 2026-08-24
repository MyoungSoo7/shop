package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다중 PG 라우터.
 *
 * <p>역할:
 * <ol>
 *   <li>{@code authorize} 시: 결제 수단·금액·판매자 등급을 보고 1 순위 PG 선택,
 *       해당 PG 가 unhealthy 면 fallback 체인으로 자동 전환</li>
 *   <li>{@code captureFor / refundFor}: 거래 ID prefix 로 원래 처리한 PG 식별 후 해당 어댑터에 위임</li>
 *   <li>각 라우팅 결정을 Prometheus 메트릭으로 노출 ({@code pg.routing.requests})</li>
 * </ol>
 *
 * <p>설계 원칙: 라우팅은 도메인 로직이 아닌 인프라 결정이므로 헥사고날 아웃바운드 어댑터 영역에 위치.
 * 도메인 (PaymentDomain, UseCase) 는 PG 가 무엇인지 모른다 — {@link PgClientPort} 만 본다.
 */
@Component
@EnableConfigurationProperties(PgRoutingProperties.class)
public class PgRouter {

    private static final Logger log = LoggerFactory.getLogger(PgRouter.class);

    private final Map<PaymentGateway, PaymentGatewayAdapter> adaptersByProvider;
    private final PgRoutingProperties properties;
    private final MeterRegistry meterRegistry;

    public PgRouter(List<PaymentGatewayAdapter> adapters,
                    PgRoutingProperties properties,
                    MeterRegistry meterRegistry) {
        // fail-closed: 결제 가능한 PG 가 하나도 없으면 기동을 거부한다.
        // List 주입은 빈 목록도 허용하므로, 프로파일 게이트로 모의 어댑터가 전부 빠진 운영 환경에서
        // 라우터만 조용히 떠 버릴 수 있다. 그러면 첫 결제 시도까지 아무도 모른 채 배포가 끝난다 —
        // 결제 불가는 기동 시점에 드러나야 하는 종류의 사실이다.
        if (adapters.isEmpty()) {
            throw new IllegalStateException(
                    "결제 가능한 PG 어댑터가 하나도 없습니다 — 운영 프로파일에서는 모의 PG(Toss/Kcp/Nice/Inicis)가 "
                            + "등록되지 않으므로 실 PG 연동 어댑터를 먼저 배선해야 합니다.");
        }
        this.adaptersByProvider = new LinkedHashMap<>();
        for (PaymentGatewayAdapter adapter : adapters) {
            this.adaptersByProvider.put(adapter.provider(), adapter);
        }
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        log.info("PgRouter initialized with adapters: {}", adaptersByProvider.keySet());
    }

    /**
     * authorize 요청에 적합한 어댑터를 결정한다.
     * 우선순위: (1) 고액 거래 우선 PG → (2) 결제수단별 1순위 → (3) fallback 체인
     * 후보가 모두 unhealthy 또는 미지원이면 마지막 fallback 도 거부 — 호출자가 IllegalStateException.
     */
    public PaymentGatewayAdapter selectFor(BigDecimal amount, String paymentMethod) {
        // 1) 고액 거래 우선 PG
        if (amount != null && amount.compareTo(properties.getHighAmountThreshold()) >= 0) {
            PaymentGatewayAdapter highAmountCandidate = candidate(properties.getHighAmountPreferred(), paymentMethod);
            if (highAmountCandidate != null) {
                recordRouting(highAmountCandidate.provider(), "high-amount", paymentMethod);
                return highAmountCandidate;
            }
        }

        // 2) 결제수단별 1 순위
        if (paymentMethod != null) {
            PaymentGateway primary = properties.getPrimaryByMethod().get(paymentMethod.toUpperCase());
            if (primary != null) {
                PaymentGatewayAdapter primaryCandidate = candidate(primary, paymentMethod);
                if (primaryCandidate != null) {
                    recordRouting(primaryCandidate.provider(), "primary", paymentMethod);
                    return primaryCandidate;
                }
            }
        }

        // 3) fallback 체인
        for (PaymentGateway pg : properties.getFallbackChain()) {
            PaymentGatewayAdapter c = candidate(pg, paymentMethod);
            if (c != null) {
                recordRouting(c.provider(), "fallback", paymentMethod);
                if (log.isDebugEnabled()) {
                    log.debug("PG fallback selected: {} for method={}", c.provider(), paymentMethod);
                }
                return c;
            }
        }

        recordRouting(PaymentGateway.MOCK, "no-candidate", paymentMethod);
        // 도메인 불변식이 아니라 PG 헬스/설정 인프라 상태 문제이므로 generic 유지(사유 명시).
        throw new IllegalStateException(
                "결제 가능한 PG 가 없습니다 (모두 unhealthy 또는 결제수단 미지원): method=" + paymentMethod);
    }

    /**
     * 이미 처리된 거래의 후속 작업 (capture / refund) 시 같은 PG 로 라우팅.
     * 거래 ID prefix 가 곧 PG 식별자 — DB 컬럼 추가 없이 이력 보존.
     */
    public PaymentGatewayAdapter resolveByTransactionId(String pgTransactionId) {
        PaymentGateway pg = PaymentGateway.fromTransactionId(pgTransactionId);
        PaymentGatewayAdapter adapter = adaptersByProvider.get(pg);
        if (adapter == null) {
            // 등록되지 않은 PG provider — 배선/설정 오류(프로그래밍 오류 가드)이므로 generic 유지(사유 명시).
            throw new IllegalStateException("어댑터 없음: provider=" + pg + ", txnId=" + pgTransactionId);
        }
        return adapter;
    }

    private PaymentGatewayAdapter candidate(PaymentGateway target, String paymentMethod) {
        PaymentGatewayAdapter adapter = adaptersByProvider.get(target);
        if (adapter == null) return null;
        if (!adapter.supports(paymentMethod)) return null;
        if (!adapter.isHealthy()) return null;
        return adapter;
    }

    private void recordRouting(PaymentGateway provider, String reason, String method) {
        Counter.builder("pg.routing.requests")
                .description("PG 라우팅 결정 카운터")
                .tag("provider", provider.name())
                .tag("reason", reason)
                .tag("method", method == null ? "UNKNOWN" : method.toUpperCase())
                .register(meterRegistry)
                .increment();
    }

    /**
     * 디버깅 / Admin API 용 — 현재 등록된 PG 와 health 상태 요약.
     */
    public Map<PaymentGateway, Boolean> healthSnapshot() {
        Map<PaymentGateway, Boolean> snapshot = new LinkedHashMap<>();
        adaptersByProvider.forEach((pg, ad) -> snapshot.put(pg, ad.isHealthy()));
        return snapshot;
    }
}
