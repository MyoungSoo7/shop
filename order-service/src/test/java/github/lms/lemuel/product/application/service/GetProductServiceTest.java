package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetProductServiceTest {

    @Mock LoadProductPort loadProductPort;
    @InjectMocks GetProductService service;

    @Test @DisplayName("getProductById: 상품 조회 성공")
    void getById_success() {
        Product product = Product.create("상품", "설명", BigDecimal.TEN, 10);
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        Product result = service.getProductById(1L);
        assertThat(result.getName()).isEqualTo("상품");
    }

    @Test @DisplayName("getProductById: 상품 없으면 예외")
    void getById_notFound() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProductById(1L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("getAllProducts: 전체 상품 조회")
    void getAll() {
        when(loadProductPort.findAll()).thenReturn(List.of());
        assertThat(service.getAllProducts()).isEmpty();
    }

    @Test @DisplayName("getProductsByStatus: 상태별 조회")
    void getByStatus() {
        when(loadProductPort.findByStatus(ProductStatus.ACTIVE)).thenReturn(List.of());
        assertThat(service.getProductsByStatus(ProductStatus.ACTIVE)).isEmpty();
    }

    @Test @DisplayName("getAvailableProducts: 판매 가능 상품 조회")
    void getAvailable() {
        when(loadProductPort.findAvailableProducts()).thenReturn(List.of());
        assertThat(service.getAvailableProducts()).isEmpty();
    }
}
