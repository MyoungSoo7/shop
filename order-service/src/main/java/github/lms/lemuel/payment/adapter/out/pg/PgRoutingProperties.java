package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * PG 라우팅 설정 — 결제수단 / 거래금액 / 판매자 등급 별로 우선순위를 부여한다.
 *
 * <p>application.yml 의 {@code app.pg.routing} 키에 매핑.
 *
 * <pre>
 * app:
 *   pg:
 *     routing:
 *       primary-by-method:
 *         CARD: TOSS
 *         KAKAO_PAY: NICE
 *         BANK_TRANSFER: KCP
 *       fallback-chain: [TOSS, NICE, KCP, INICIS]
 *       high-amount-threshold: 1000000
 *       high-amount-preferred: NICE
 * </pre>
 *
 * <p>운영 시점에 기본 PG 정책을 코드 변경 없이 외부 설정으로 바꿀 수 있다 — 면접 어필 포인트.
 */
@ConfigurationProperties(prefix = "app.pg.routing")
public class PgRoutingProperties {

    /**
     * 결제 수단 → 1 순위 PG 매핑.
     */
    private Map<String, PaymentGateway> primaryByMethod = Map.of();

    /**
     * 모든 후보가 부적합하거나 OPEN 일 때의 시도 순서 (좌→우).
     */
    private List<PaymentGateway> fallbackChain = List.of(
            PaymentGateway.TOSS,
            PaymentGateway.NICE,
            PaymentGateway.KCP,
            PaymentGateway.INICIS
    );

    /**
     * 이 금액 이상이면 {@link #highAmountPreferred} 로 우선 라우팅.
     * (대형 결제는 안정성 / 한도 / 수수료 협상이 유리한 PG 로)
     */
    private BigDecimal highAmountThreshold = new BigDecimal("1000000");

    private PaymentGateway highAmountPreferred = PaymentGateway.NICE;

    public Map<String, PaymentGateway> getPrimaryByMethod() {
        return primaryByMethod;
    }

    public void setPrimaryByMethod(Map<String, PaymentGateway> primaryByMethod) {
        this.primaryByMethod = primaryByMethod;
    }

    public List<PaymentGateway> getFallbackChain() {
        return fallbackChain;
    }

    public void setFallbackChain(List<PaymentGateway> fallbackChain) {
        this.fallbackChain = fallbackChain;
    }

    public BigDecimal getHighAmountThreshold() {
        return highAmountThreshold;
    }

    public void setHighAmountThreshold(BigDecimal highAmountThreshold) {
        this.highAmountThreshold = highAmountThreshold;
    }

    public PaymentGateway getHighAmountPreferred() {
        return highAmountPreferred;
    }

    public void setHighAmountPreferred(PaymentGateway highAmountPreferred) {
        this.highAmountPreferred = highAmountPreferred;
    }
}
