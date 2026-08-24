package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
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
 * 원자적 조건부 UPDATE 기반 재고 차감 단위 테스트.
 *
 * <p>실 PostgreSQL 동시성은 별도 통합 테스트로 검증. 여기서는 영향 행 수(1/0)에 따른 성공/거절
 * 분기와 거절 원인(재고부족·단종·미존재) 분류 로직을 격리 검증한다.
 */
class DecreaseVariantStockServiceTest {

    private LoadProductVariantPort loadPort;
    private SaveProductVariantPort savePort;
    private DecreaseVariantStockService service;

    @BeforeEach
    void setup() {
        loadPort = mock(LoadProductVariantPort.class);
        savePort = mock(SaveProductVariantPort.class);
        // 트랜잭션을 직접 실행시키는 fake — TransactionCallback 을 그대로 호출
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new DecreaseVariantStockService(loadPort, savePort, txTemplate, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("정상 차감: 원자 UPDATE 1 행 → 갱신된 variant 반환")
    void decreasesAtomically() {
        ProductVariant afterDecrease = variant(45, ProductVariantStatus.ACTIVE);
        when(savePort.decreaseStockIfAvailable(1L, 5)).thenReturn(1);
        when(loadPort.loadById(1L)).thenReturn(Optional.of(afterDecrease));

        ProductVariant result = service.decrease(1L, 5);

        assertThat(result).isNotNull();
        assertThat(result.getStockQuantity()).isEqualTo(45);
        verify(savePort, times(1)).decreaseStockIfAvailable(1L, 5);
        // 성공 경로는 차감 후 상태 조회 1 회만 (원인 분류 조회 없음)
        verify(loadPort, times(1)).loadById(1L);
    }

    @Test
    @DisplayName("재고 부족: 영향 행 0 + ACTIVE → InsufficientStockException")
    void insufficientStock() {
        when(savePort.decreaseStockIfAvailable(1L, 5)).thenReturn(0);
        when(loadPort.loadById(1L)).thenReturn(Optional.of(variant(1, ProductVariantStatus.ACTIVE)));

        assertThatThrownBy(() -> service.decrease(1L, 5))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("단종 SKU: 영향 행 0 + DISCONTINUED → IllegalStateException")
    void discontinued() {
        when(savePort.decreaseStockIfAvailable(1L, 1)).thenReturn(0);
        when(loadPort.loadById(1L)).thenReturn(Optional.of(variant(50, ProductVariantStatus.DISCONTINUED)));

        assertThatThrownBy(() -> service.decrease(1L, 1))
                .isInstanceOf(InvalidProductStateException.class)
                .hasMessageContaining("단종");
    }

    @Test
    @DisplayName("variant 미존재: 영향 행 0 + 조회 empty → IllegalArgumentException")
    void unknownVariant() {
        when(savePort.decreaseStockIfAvailable(999L, 1)).thenReturn(0);
        when(loadPort.loadById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decrease(999L, 1))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test
    @DisplayName("수량 <= 0: DB 접근 없이 IllegalArgumentException")
    void nonPositiveQuantity() {
        assertThatThrownBy(() -> service.decrease(1L, 0))
                .isInstanceOf(ProductInvariantViolationException.class);

        verify(savePort, never()).decreaseStockIfAvailable(anyLong(), anyInt());
    }

    private static ProductVariant variant(int stock, ProductVariantStatus status) {
        return ProductVariant.rehydrate(1L, 10L, "SKU", "옵션",
                BigDecimal.ZERO, stock, 0L, status, null, null);
    }
}
