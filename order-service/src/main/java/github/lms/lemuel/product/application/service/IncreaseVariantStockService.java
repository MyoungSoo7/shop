package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 옵션(SKU) 재고 원복(증가) — 원자적 조건부 UPDATE 기반.
 *
 * <p>{@link DecreaseVariantStockService} 의 역연산. 환불/취소 승인 시 차감했던 SKU 재고를 되돌린다.
 * 호출자의 트랜잭션에 참여하며, 단종·미존재로 원복 불가(영향 행 0)면 예외 없이 경고 로그만 남긴다.
 */
@Service
@Transactional
public class IncreaseVariantStockService implements IncreaseVariantStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncreaseVariantStockService.class);

    private final SaveProductVariantPort savePort;

    public IncreaseVariantStockService(SaveProductVariantPort savePort) {
        this.savePort = savePort;
    }

    @Override
    public boolean increase(Long variantId, int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("원복 수량은 양수여야 합니다");
        }
        int affected = savePort.increaseStock(variantId, quantity);
        if (affected == 0) {
            log.warn("SKU 재고 원복 스킵(단종·미존재): variantId={}, qty={}", variantId, quantity);
            return false;
        }
        return true;
    }
}
