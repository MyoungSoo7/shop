package github.lms.lemuel.product.adapter.in.web.request;

import github.lms.lemuel.product.application.port.in.CreateProductUseCase.CreateProductCommand;

import java.math.BigDecimal;

public record CreateProductRequest(
        String name,
        String description,
        BigDecimal price,
        Integer stockQuantity,
        String optionsJson
) {
    public CreateProductCommand toCommand() {
        return new CreateProductCommand(name, description, price, stockQuantity, optionsJson);
    }
}
