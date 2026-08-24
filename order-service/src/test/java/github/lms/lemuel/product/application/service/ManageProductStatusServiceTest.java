package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManageProductStatusServiceTest {

    @Mock LoadProductPort loadProductPort;
    @Mock SaveProductPort saveProductPort;
    @InjectMocks ManageProductStatusService service;

    private Product createProduct() {
        return Product.create("상품", "설명", new BigDecimal("10000"), 50);
    }

    @Test @DisplayName("activateProduct: 활성화 성공")
    void activate() {
        Product product = createProduct();
        product.deactivate();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Product result = service.activateProduct(1L);
        assertThat(result.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test @DisplayName("deactivateProduct: 비활성화 성공")
    void deactivate() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Product result = service.deactivateProduct(1L);
        assertThat(result.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    @Test @DisplayName("discontinueProduct: 단종 성공")
    void discontinue() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Product result = service.discontinueProduct(1L);
        assertThat(result.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
    }

    @Test @DisplayName("activateProduct: 상품 없으면 예외")
    void activate_notFound() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activateProduct(1L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("deactivateProduct: 상품 없으면 예외")
    void deactivate_notFound() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateProduct(1L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("discontinueProduct: 상품 없으면 예외")
    void discontinue_notFound() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.discontinueProduct(1L))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
