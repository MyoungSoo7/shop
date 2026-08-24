package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductResponseTest {

    @Test
    @DisplayName("from(product): primaryImageUrl 없이 생성 시 null")
    void from_singleArg() {
        Product product = Product.create("상품A", "설명", new BigDecimal("1000"), 5);
        product.assignId(1L);

        ProductResponse response = ProductResponse.from(product);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("상품A");
        assertThat(response.price()).isEqualByComparingTo("1000");
        assertThat(response.stockQuantity()).isEqualTo(5);
        assertThat(response.primaryImageUrl()).isNull();
        assertThat(response.availableForSale()).isTrue();
    }

    @Test
    @DisplayName("from(product, url): primaryImageUrl 포함해 생성")
    void from_withImageUrl() {
        Product product = Product.rehydrate(2L, "상품B", "설명", new BigDecimal("2000"), 0,
                ProductStatus.ACTIVE, 9L, null, "{\"tree\":true}", null, null);

        ProductResponse response = ProductResponse.from(product, "/img/2.jpg");

        assertThat(response.primaryImageUrl()).isEqualTo("/img/2.jpg");
        assertThat(response.categoryId()).isEqualTo(9L);
        assertThat(response.optionsJson()).isEqualTo("{\"tree\":true}");
        assertThat(response.availableForSale()).isFalse();
        assertThat(response.status()).isEqualTo(product.getStatus());
        assertThat(response.createdAt()).isEqualTo(product.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(product.getUpdatedAt());
    }
}
