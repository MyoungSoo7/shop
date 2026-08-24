package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.product.application.port.in.CreateProductUseCase;
import github.lms.lemuel.product.application.port.in.GetProductUseCase;
import github.lms.lemuel.product.application.port.in.ManageProductStatusUseCase;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase.StockOperation;
import github.lms.lemuel.product.application.service.ProductImageService;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreateProductUseCase createProductUseCase;
    @MockitoBean GetProductUseCase getProductUseCase;
    @MockitoBean UpdateProductUseCase updateProductUseCase;
    @MockitoBean ManageProductStatusUseCase manageProductStatusUseCase;
    @MockitoBean ProductImageService productImageService;
    @MockitoBean github.lms.lemuel.product.application.port.in.SearchProductFacetsUseCase searchProductFacetsUseCase;

    private static Product product(Long id, String name) {
        Product p = Product.create(name, "설명", new BigDecimal("1000"), 10);
        p.assignId(id);
        return p;
    }

    @Test
    @DisplayName("POST /api/products creates product")
    void createProduct() throws Exception {
        when(createProductUseCase.createProduct(any())).thenReturn(product(1L, "상품A"));
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"상품A","description":"설명","price":1000,"stockQuantity":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("상품A"));
    }

    @Test
    @DisplayName("GET /api/products/{id} returns product")
    void getProduct() throws Exception {
        when(getProductUseCase.getProductById(1L)).thenReturn(product(1L, "상품A"));
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn("/img/1.jpg");

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.primaryImageUrl").value("/img/1.jpg"));
    }

    @Test
    @DisplayName("GET /api/products returns all products (no filter)")
    void getAllProducts() throws Exception {
        when(getProductUseCase.getAllProducts()).thenReturn(List.of(product(1L, "상품A")));
        when(productImageService.getPrimaryImageUrl(anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("GET /api/products with keyword delegates to search")
    void getAllProducts_withKeyword() throws Exception {
        when(getProductUseCase.searchProducts("shoe", null, "latest", "DESC"))
                .thenReturn(List.of(product(2L, "신발")));
        when(productImageService.getPrimaryImageUrl(anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/products").param("keyword", "shoe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("신발"));
    }

    @Test
    @DisplayName("GET /api/products/search returns matches")
    void searchProducts() throws Exception {
        when(getProductUseCase.searchProducts("shoe", 3L, "price", "ASC"))
                .thenReturn(List.of(product(2L, "신발")));
        when(productImageService.getPrimaryImageUrl(anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/products/search")
                        .param("keyword", "shoe")
                        .param("categoryId", "3")
                        .param("sortBy", "price")
                        .param("sortDirection", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2));
    }

    @Test
    @DisplayName("GET /api/products/status/{status} filters by status")
    void getProductsByStatus() throws Exception {
        when(getProductUseCase.getProductsByStatus(ProductStatus.ACTIVE))
                .thenReturn(List.of(product(1L, "상품A")));
        when(productImageService.getPrimaryImageUrl(anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/products/status/ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/products/available returns sellable products")
    void getAvailableProducts() throws Exception {
        when(getProductUseCase.getAvailableProducts()).thenReturn(List.of(product(1L, "상품A")));
        when(productImageService.getPrimaryImageUrl(anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/products/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("PUT /api/products/{id}/info updates name/description")
    void updateProductInfo() throws Exception {
        Product updated = product(1L, "변경됨");
        when(updateProductUseCase.updateProductInfo(any())).thenReturn(updated);
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(put("/api/products/1/info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"변경됨","description":"새설명"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("변경됨"));
    }

    @Test
    @DisplayName("PUT /api/products/{id}/price updates price")
    void updateProductPrice() throws Exception {
        Product updated = product(1L, "상품A");
        when(updateProductUseCase.updateProductPrice(any())).thenReturn(updated);
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(put("/api/products/1/price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPrice":2000}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/products/{id}/stock updates stock")
    void updateProductStock() throws Exception {
        Product updated = product(1L, "상품A");
        when(updateProductUseCase.updateProductStock(any())).thenReturn(updated);
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(put("/api/products/1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":5,"operation":"INCREASE"}
                                """))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(updateProductUseCase).updateProductStock(
                new UpdateProductUseCase.UpdateProductStockCommand(1L, 5, StockOperation.INCREASE));
    }

    @Test
    @DisplayName("POST /api/products/{id}/activate activates product")
    void activateProduct() throws Exception {
        when(manageProductStatusUseCase.activateProduct(1L)).thenReturn(product(1L, "상품A"));
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(post("/api/products/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/products/{id}/deactivate deactivates product")
    void deactivateProduct() throws Exception {
        Product p = product(1L, "상품A");
        p.deactivate();
        when(manageProductStatusUseCase.deactivateProduct(1L)).thenReturn(p);
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(post("/api/products/1/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("POST /api/products/{id}/discontinue discontinues product")
    void discontinueProduct() throws Exception {
        Product p = product(1L, "상품A");
        p.discontinue();
        when(manageProductStatusUseCase.discontinueProduct(1L)).thenReturn(p);
        when(productImageService.getPrimaryImageUrl(1L)).thenReturn(null);

        mockMvc.perform(post("/api/products/1/discontinue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCONTINUED"));
    }
}
