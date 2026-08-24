package github.lms.lemuel.product.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 재고 차감 시 Optimistic Lock 충돌이 재시도 한계 안에 해소되지 않을 때 발생.
 *
 * <p>운영 시에는 클라이언트가 일정 시간 후 재시도하거나, 같은 SKU 가 매우 hot 한 경우
 * Redis 기반 분산 락 / Pessimistic Lock 으로 격상하는 의사결정이 필요하다는 신호.
 *
 * <p>재시도로 해소되지 않은 경합이므로 HTTP 409(Conflict)로 매핑된다({@link ErrorCode#STOCK_CONCURRENCY}).
 */
public class StockConcurrencyException extends BusinessException {
    public StockConcurrencyException(String message) {
        super(ErrorCode.STOCK_CONCURRENCY, message);
    }

    public StockConcurrencyException(String message, Throwable cause) {
        super(ErrorCode.STOCK_CONCURRENCY, message, cause);
    }
}
