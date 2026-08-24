package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.UpdateProductUseCase;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase.StockOperation;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
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
class UpdateProductServiceTest {

    @Mock LoadProductPort loadProductPort;
    @Mock SaveProductPort saveProductPort;
    @Mock github.lms.lemuel.product.application.port.out.PublishProductEventPort publishProductEventPort;
    @InjectMocks UpdateProductService service;

    private Product createProduct() {
        return Product.create("상품", "설명", new BigDecimal("10000"), 50);
    }

    @Test @DisplayName("updateProductInfo: 정보 수정 성공")
    void updateInfo_success() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var cmd = new UpdateProductUseCase.UpdateProductInfoCommand(1L, "새이름", "새설명");
        Product result = service.updateProductInfo(cmd);
        assertThat(result.getName()).isEqualTo("새이름");
    }

    @Test @DisplayName("updateProductInfo: 상품 없으면 예외")
    void updateInfo_notFound() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.empty());
        var cmd = new UpdateProductUseCase.UpdateProductInfoCommand(1L, "이름", "설명");
        assertThatThrownBy(() -> service.updateProductInfo(cmd))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("updateProductPrice: 가격 수정")
    void updatePrice() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var cmd = new UpdateProductUseCase.UpdateProductPriceCommand(1L, new BigDecimal("20000"));
        Product result = service.updateProductPrice(cmd);
        assertThat(result.getPrice()).isEqualByComparingTo("20000");
    }

    @Test @DisplayName("updateProductStock: 재고 증가")
    void updateStock_increase() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var cmd = new UpdateProductUseCase.UpdateProductStockCommand(1L, 10, StockOperation.INCREASE);
        Product result = service.updateProductStock(cmd);
        assertThat(result.getStockQuantity()).isEqualTo(60);
    }

    @Test @DisplayName("updateProductStock: 재고 감소")
    void updateStock_decrease() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        when(saveProductPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var cmd = new UpdateProductUseCase.UpdateProductStockCommand(1L, 10, StockOperation.DECREASE);
        Product result = service.updateProductStock(cmd);
        assertThat(result.getStockQuantity()).isEqualTo(40);
    }

    @Test @DisplayName("updateProductStock: 재고 부족이면 InsufficientStockException")
    void updateStock_insufficient() {
        Product product = createProduct();
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(product));
        var cmd = new UpdateProductUseCase.UpdateProductStockCommand(1L, 100, StockOperation.DECREASE);
        assertThatThrownBy(() -> service.updateProductStock(cmd))
                .isInstanceOf(InsufficientStockException.class);
    }
}
