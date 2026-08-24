package github.lms.lemuel.product.adapter.in.web.request;

import java.math.BigDecimal;

public record UpdateProductPriceRequest(
        BigDecimal newPrice
) {
}
