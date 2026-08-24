package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.product.application.port.in.CreateProductVariantUseCase;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductVariantController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductVariantControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreateProductVariantUseCase createUseCase;
    @MockitoBean DecreaseVariantStockUseCase decreaseStockUseCase;
    @MockitoBean LoadProductVariantPort loadPort;
    @MockitoBean ResolveOptionSelectionUseCase resolveUseCase;

    /** 할인 미설정(null)이 기본이다 — 대부분의 SKU 는 할인이 없다. */
    private static ProductVariant variant(Long id) {
        return ProductVariant.rehydrate(id, 1L, "SKU-1", "색상:빨강", new BigDecimal("500"),
                null, null, 10, 0L, github.lms.lemuel.product.domain.ProductVariantStatus.ACTIVE,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /products/{productId}/variants creates variant")
    void create() throws Exception {
        when(createUseCase.create(1L, "SKU-1", "색상:빨강", new BigDecimal("500"), 10))
                .thenReturn(variant(100L));

        mockMvc.perform(post("/products/1/variants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-1","optionName":"색상:빨강","additionalPrice":500,"initialStock":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant.sku").value("SKU-1"));
    }

    @Test
    @DisplayName("GET /products/{productId}/variants lists variants")
    void list() throws Exception {
        when(loadPort.loadByProductId(1L)).thenReturn(List.of(variant(100L)));

        mockMvc.perform(get("/products/1/variants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].variant.id").value(100));
    }

    @Test
    @DisplayName("POST /products/{productId}/variants/{variantId}/decrease-stock decreases stock")
    void decreaseStock() throws Exception {
        ProductVariant v = variant(100L);
        v.decreaseStock(3);
        when(decreaseStockUseCase.decrease(100L, 3)).thenReturn(v);

        mockMvc.perform(post("/products/1/variants/100/decrease-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant.stockQuantity").value(7));
    }

    @Test
    @DisplayName("POST /products/{productId}/variants/resolve resolves selection to variant")
    void resolve() throws Exception {
        when(resolveUseCase.resolve(1L, List.of(
                new ResolveOptionSelectionUseCase.Selection("색상", "빨강"))))
                .thenReturn(variant(100L));

        mockMvc.perform(post("/products/1/variants/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selections":[{"name":"색상","value":"빨강"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant.id").value(100));
    }

    @Test
    @DisplayName("할인 미설정 SKU 도 200 으로 직렬화된다 — Map.of 가 null 을 거부해 500 이 나던 회귀")
    void serializesVariantWithoutDiscounts() throws Exception {
        when(loadPort.loadByProductId(1L)).thenReturn(List.of(variant(100L)));

        mockMvc.perform(get("/products/1/variants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].variant.id").value(100))
                .andExpect(jsonPath("$[0].variant.discountPrice").doesNotExist())
                .andExpect(jsonPath("$[0].variant.discountRate").doesNotExist());
    }
}
