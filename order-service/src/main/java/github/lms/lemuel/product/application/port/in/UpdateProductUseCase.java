package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.math.BigDecimal;

public interface UpdateProductUseCase {

    Product updateProductInfo(UpdateProductInfoCommand command);

    Product updateProductPrice(UpdateProductPriceCommand command);

    Product updateProductStock(UpdateProductStockCommand command);

    record UpdateProductInfoCommand(
            Long productId,
            String name,
            String description
    ) {
        public UpdateProductInfoCommand {
            if (productId == null) {
                throw new ProductInvariantViolationException("Product ID cannot be null");
            }
        }
    }

    record UpdateProductPriceCommand(
            Long productId,
            BigDecimal newPrice
    ) {
        public UpdateProductPriceCommand {
            if (productId == null) {
                throw new ProductInvariantViolationException("Product ID cannot be null");
            }
            if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) < 0) {
                throw new ProductInvariantViolationException("New price must be zero or greater");
            }
        }
    }

    record UpdateProductStockCommand(
            Long productId,
            int quantity,
            StockOperation operation
    ) {
        public UpdateProductStockCommand {
            if (productId == null) {
                throw new ProductInvariantViolationException("Product ID cannot be null");
            }
            if (quantity <= 0) {
                throw new ProductInvariantViolationException("Quantity must be positive");
            }
            if (operation == null) {
                throw new ProductInvariantViolationException("Stock operation cannot be null");
            }
        }
    }

    enum StockOperation {
        INCREASE,
        DECREASE
    }
}
