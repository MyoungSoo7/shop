package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.math.BigDecimal;

public interface CreateProductUseCase {

    Product createProduct(CreateProductCommand command);

    record CreateProductCommand(
            String name,
            String description,
            BigDecimal price,
            Integer stockQuantity,
            String optionsJson
    ) {
        public CreateProductCommand {
            if (name == null || name.trim().isEmpty()) {
                throw new ProductInvariantViolationException("Product name cannot be empty");
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
                throw new ProductInvariantViolationException("Price must be zero or greater");
            }
            if (stockQuantity == null || stockQuantity < 0) {
                throw new ProductInvariantViolationException("Stock quantity must be zero or greater");
            }
        }

        /** 옵션 트리 없는 상품 (기존 호출 호환). */
        public CreateProductCommand(String name, String description, BigDecimal price, Integer stockQuantity) {
            this(name, description, price, stockQuantity, null);
        }
    }
}
