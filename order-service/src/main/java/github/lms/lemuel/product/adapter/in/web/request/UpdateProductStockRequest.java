package github.lms.lemuel.product.adapter.in.web.request;

import github.lms.lemuel.product.application.port.in.UpdateProductUseCase.StockOperation;

public record UpdateProductStockRequest(
        int quantity,
        StockOperation operation
) {
}
