package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DecreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.common.opssignal.NoOpOpsSignalPublisher;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * 옵션 없는 일반 상품 재고 차감 — 원자적 조건부 UPDATE 기반 동시성 제어.
 *
 * <p>단 한 번의
 * {@code UPDATE ... SET stock = stock - q WHERE id = ? AND stock >= q AND status <> DISCONTINUED}
 * 로 "재고 검증 + 차감 + 매진 전이" 를 DB row 락 안에서 원자적으로 처리해, 락 대기·재시도 없이
 * 초과판매를 방지한다. 동시성·관측·실패 분류 골격은 {@link AbstractDecreaseStockService} 가 소유하고,
 * 여기서는 일반 상품에 특화된 훅(포트·예외·ops 신호)만 구현한다.
 *
 * <p>운영 메트릭:
 * <ul>
 *   <li>{@code product.stock.decrease.success} — 차감 성공 누적</li>
 *   <li>{@code product.stock.decrease.rejected} — 재고 부족·단종 등으로 거절된 누적</li>
 * </ul>
 */
@Service
public class DecreaseProductStockService extends AbstractDecreaseStockService<Product>
        implements DecreaseProductStockUseCase {

    private final LoadProductPort loadPort;
    private final SaveProductPort savePort;
    private final OpsSignalPort opsSignalPort;

    /** 운영 컨텍스트용 — Spring 이 이 생성자로 실 OpsSignalPort 빈을 주입한다. */
    @Autowired
    public DecreaseProductStockService(LoadProductPort loadPort,
                                       SaveProductPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry,
                                       OpsSignalPort opsSignalPort) {
        super(transactionTemplate, meterRegistry, "product.stock.decrease", "일반 상품 재고 차감 성공 누적");
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.opsSignalPort = opsSignalPort;
    }

    /** 기존 테스트/수동 조립 호환 편의 생성자 — ops 신호는 no-op. */
    public DecreaseProductStockService(LoadProductPort loadPort,
                                       SaveProductPort savePort,
                                       TransactionTemplate transactionTemplate,
                                       MeterRegistry meterRegistry) {
        this(loadPort, savePort, transactionTemplate, meterRegistry, new NoOpOpsSignalPublisher());
    }

    @Override
    public Product decrease(Long productId, int quantity) {
        return doDecrease(productId, quantity);
    }

    @Override
    protected int decreaseStockIfAvailable(Long id, int quantity) {
        return savePort.decreaseStockIfAvailable(id, quantity);
    }

    @Override
    protected Optional<Product> reload(Long id) {
        return loadPort.findById(id);
    }

    @Override
    protected RuntimeException notFound(Long id) {
        return new ProductNotFoundException(id);
    }

    @Override
    protected RuntimeException classifyPresent(Product current, Long id, int quantity) {
        if (current.getStatus() == ProductStatus.DISCONTINUED) {
            return new InvalidProductStateException("단종된 상품은 차감할 수 없습니다: id=" + id);
        }
        // 운영 관제 신호 — 구매 시 재고 부족(초과 수요). best-effort(절대 throw 안 함).
        opsSignalPort.emit(OpsSignalCategory.STOCK_DEPLETED, "product", String.valueOf(id),
                Map.of("requested", quantity, "available", current.getStockQuantity()));
        return new InsufficientStockException(
                "재고 부족: productId=" + id + ", 요청=" + quantity
                        + ", 가용=" + current.getStockQuantity());
    }
}
