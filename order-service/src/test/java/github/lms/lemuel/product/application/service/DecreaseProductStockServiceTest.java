package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SaveProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 일반 상품(옵션 없는) 원자적 조건부 UPDATE 재고 차감 단위 테스트.
 *
 * <p>옵션 상품({@link DecreaseVariantStockServiceTest}) 과 동일한 분기 — 영향 행 수(1/0) 에 따른
 * 성공/거절과 거절 원인(재고부족·단종·미존재) 분류 로직을 격리 검증한다.
 */
class DecreaseProductStockServiceTest {

    private LoadProductPort loadPort;
    private SaveProductPort savePort;
    private DecreaseProductStockService service;

    @BeforeEach
    void setup() {
        loadPort = mock(LoadProductPort.class);
        savePort = mock(SaveProductPort.class);
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new DecreaseProductStockService(loadPort, savePort, txTemplate, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("정상 차감: 원자 UPDATE 1 행 → 갱신된 product 반환")
    void decreasesAtomically() {
        when(savePort.decreaseStockIfAvailable(1L, 5)).thenReturn(1);
        when(loadPort.findById(1L)).thenReturn(Optional.of(product(45, ProductStatus.ACTIVE)));

        Product result = service.decrease(1L, 5);

        assertThat(result).isNotNull();
        assertThat(result.getStockQuantity()).isEqualTo(45);
        verify(savePort, times(1)).decreaseStockIfAvailable(1L, 5);
        verify(loadPort, times(1)).findById(1L);
    }

    @Test
    @DisplayName("재고 부족: 영향 행 0 + ACTIVE → InsufficientStockException")
    void insufficientStock() {
        when(savePort.decreaseStockIfAvailable(1L, 5)).thenReturn(0);
        when(loadPort.findById(1L)).thenReturn(Optional.of(product(1, ProductStatus.ACTIVE)));

        assertThatThrownBy(() -> service.decrease(1L, 5))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("단종 상품: 영향 행 0 + DISCONTINUED → IllegalStateException")
    void discontinued() {
        when(savePort.decreaseStockIfAvailable(1L, 1)).thenReturn(0);
        when(loadPort.findById(1L)).thenReturn(Optional.of(product(50, ProductStatus.DISCONTINUED)));

        assertThatThrownBy(() -> service.decrease(1L, 1))
                .isInstanceOf(InvalidProductStateException.class)
                .hasMessageContaining("단종");
    }

    @Test
    @DisplayName("product 미존재: 영향 행 0 + 조회 empty → ProductNotFoundException")
    void unknownProduct() {
        when(savePort.decreaseStockIfAvailable(999L, 1)).thenReturn(0);
        when(loadPort.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decrease(999L, 1))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("수량 <= 0: DB 접근 없이 IllegalArgumentException")
    void nonPositiveQuantity() {
        assertThatThrownBy(() -> service.decrease(1L, 0))
                .isInstanceOf(ProductInvariantViolationException.class);

        verify(savePort, never()).decreaseStockIfAvailable(anyLong(), anyInt());
    }

    private static Product product(int stock, ProductStatus status) {
        return Product.rehydrate(1L, "상품", null, BigDecimal.TEN, stock, status,
                null, null, null, null, null);
    }
}
