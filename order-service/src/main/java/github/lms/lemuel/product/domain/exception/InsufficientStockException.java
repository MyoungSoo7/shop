package github.lms.lemuel.product.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

public class InsufficientStockException extends BusinessException {

    private final Long productId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(Long productId, int requestedQuantity, int availableQuantity) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                String.format("Insufficient stock for product %d: requested=%d, available=%d",
                        productId, requestedQuantity, availableQuantity),
                buildDetails(productId, requestedQuantity, availableQuantity));
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    // Map.of 는 null 값을 허용하지 않으므로(미영속 상품은 productId 가 null) null-안전하게 구성한다
    private static Map<String, Object> buildDetails(Long productId, int requestedQuantity, int availableQuantity) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (productId != null) {
            details.put("productId", productId);
        }
        details.put("requestedQuantity", requestedQuantity);
        details.put("availableQuantity", availableQuantity);
        return details;
    }

    /**
     * SKU(Variant) 재고 부족 — productId 가 sku 문자열을 통해 간접 식별되는 경우 사용.
     */
    public InsufficientStockException(String message) {
        super(ErrorCode.INSUFFICIENT_STOCK, message);
        this.productId = null;
        this.requestedQuantity = 0;
        this.availableQuantity = 0;
    }

    public Long getProductId() {
        return productId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
