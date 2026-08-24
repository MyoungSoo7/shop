package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 옵션 없는 일반 상품 재고 원복(증가) — 원자적 조건부 UPDATE 기반.
 *
 * <p>{@link DecreaseProductStockService} 의 역연산. 환불/취소 승인 시 차감했던 재고를 되돌린다.
 * 호출자의 트랜잭션에 참여하므로 별도 트랜잭션을 열지 않는다.
 *
 * <p>단종·미존재로 원복이 불가능한 경우(영향 행 0)에는 예외를 던지지 않고 경고 로그만 남긴다 —
 * 이미 성공한 PG 환불을 재고 원복 실패가 롤백시키지 않도록 하기 위함이다.
 */
@Service
@Transactional
public class IncreaseProductStockService implements IncreaseProductStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncreaseProductStockService.class);

    private final SaveProductPort savePort;

    public IncreaseProductStockService(SaveProductPort savePort) {
        this.savePort = savePort;
    }

    @Override
    public boolean increase(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("원복 수량은 양수여야 합니다");
        }
        int affected = savePort.increaseStock(productId, quantity);
        if (affected == 0) {
            log.warn("상품 재고 원복 스킵(단종·미존재): productId={}, qty={}", productId, quantity);
            return false;
        }
        return true;
    }
}
