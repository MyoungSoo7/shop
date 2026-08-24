package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.CreateProductUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.DuplicateProductNameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateProductServiceTest {

    @Mock LoadProductPort loadProductPort;
    @Mock SaveProductPort saveProductPort;
    @Mock github.lms.lemuel.product.application.port.out.PublishProductEventPort publishProductEventPort;
    @InjectMocks CreateProductService service;

    @Test @DisplayName("createProduct: 성공")
    void createProduct_success() {
        var cmd = new CreateProductUseCase.CreateProductCommand("상품", "설명", new BigDecimal("10000"), 100);
        when(loadProductPort.existsByName("상품")).thenReturn(false);
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Product result = service.createProduct(cmd);
        assertThat(result.getName()).isEqualTo("상품");
        verify(saveProductPort).save(any());
    }

    @Test @DisplayName("createProduct: 중복 상품명이면 예외")
    void createProduct_duplicate() {
        var cmd = new CreateProductUseCase.CreateProductCommand("중복", "설명", BigDecimal.TEN, 10);
        when(loadProductPort.existsByName("중복")).thenReturn(true);
        assertThatThrownBy(() -> service.createProduct(cmd))
                .isInstanceOf(DuplicateProductNameException.class);
    }
}
