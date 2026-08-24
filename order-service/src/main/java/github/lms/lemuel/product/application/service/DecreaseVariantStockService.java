package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.common.opssignal.NoOpOpsSignalPublisher;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * 옵션(SKU) 재고 차감 — 원자적 조건부 UPDATE 기반 동시성 제어.
 *
 * <p>단 한 번의 {@code UPDATE ... SET stock = stock - q WHERE id = ? AND stock >= q AND status <> DISCONTINUED}
 * 로 "재고 검증 + 차감 + 매진 전이" 를 DB row 락 안에서 원자적으로 처리한다. 낙관적 락(@Version)+재시도
 * 방식과 달리, 핫딜로 같은 SKU 에 차감이 폭주해도 락 대기·충돌 재시도·재시도 한계 실패가 없고
 * 초과판매도 방지된다.
 *
 * <p>동시성·관측·실패 분류 골격은 {@link AbstractDecreaseStockService} 가 소유하고, 여기서는
 * SKU 에 특화된 훅만 구현한다.
 *
 * <p>운영 메트릭:
 * <ul>
 *   <li>{@code variant.stock.decrease.success} — 차감 성공 누적</li>
 *   <li>{@code variant.stock.decrease.rejected} — 재고 부족·단종 등으로 차감 거절된 누적</li>
 * </ul>
 */
@Service
public class DecreaseVariantStockService extends AbstractDecreaseStockService<ProductVariant>
        implements DecreaseVariantStockUseCase {

    private final LoadProductVariantPort loadPort;
    private final SaveProductVariantPort savePort;
    private final OpsSignalPort opsSignalPort;

    /** 운영 컨텍스트용 — Spring 이 이 생성자로 실 OpsSignalPort 빈을 주입한다. */
    @Autowired
    public DecreaseVariantStockService(LoadProductVariantPort loadPort,
                                       SaveProductVariantPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry,
                                       OpsSignalPort opsSignalPort) {
        super(transactionTemplate, meterRegistry, "variant.stock.decrease", "Variant 재고 차감 성공 누적");
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.opsSignalPort = opsSignalPort;
    }

    /** 기존 테스트/수동 조립 호환 편의 생성자 — ops 신호는 no-op. */
    public DecreaseVariantStockService(LoadProductVariantPort loadPort,
                                       SaveProductVariantPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry) {
        this(loadPort, savePort, transactionTemplate, meterRegistry, new NoOpOpsSignalPublisher());
    }

    @Override
    public ProductVariant decrease(Long variantId, int quantity) {
        return doDecrease(variantId, quantity);
    }

    /**
     * 외부에서 트랜잭션을 가지고 들어온 환경 (e.g. 결제 트랜잭션 안) 에서 사용할 진입점.
     * 내부에서 {@code REQUIRES_NEW} 로 새 트랜잭션을 열어 차감을 독립 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductVariant decreaseInNewTransaction(Long variantId, int quantity) {
        return decrease(variantId, quantity);
    }

    @Override
    protected int decreaseStockIfAvailable(Long id, int quantity) {
        return savePort.decreaseStockIfAvailable(id, quantity);
    }

    @Override
    protected Optional<ProductVariant> reload(Long id) {
        return loadPort.loadById(id);
    }

    @Override
    protected RuntimeException notFound(Long id) {
        return new ProductInvariantViolationException("ProductVariant not found: " + id);
    }

    @Override
    protected RuntimeException classifyPresent(ProductVariant current, Long id, int quantity) {
        if (current.getStatus() == ProductVariantStatus.DISCONTINUED) {
            return new InvalidProductStateException("단종된 SKU 는 차감할 수 없습니다: " + current.getSku());
        }
        // 운영 관제 신호 — 구매 시 SKU 재고 부족(초과 수요). best-effort(절대 throw 안 함).
        opsSignalPort.emit(OpsSignalCategory.STOCK_DEPLETED, "variant", String.valueOf(id),
                Map.of("sku", current.getSku(), "requested", quantity, "available", current.getStockQuantity()));
        return new InsufficientStockException(
                "재고 부족: sku=" + current.getSku() + ", 요청=" + quantity
                        + ", 가용=" + current.getStockQuantity());
    }
}
