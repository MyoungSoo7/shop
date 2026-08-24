package github.lms.lemuel.product.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProductExceptionTest {

    @Test @DisplayName("DuplicateProductNameException: 상품명이 메시지에 포함된다")
    void duplicateProductName_message() {
        var ex = new DuplicateProductNameException("테스트 상품");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Product already exists with name: 테스트 상품");
    }

    @Test @DisplayName("InsufficientStockException: 3인자 생성자 — 메시지와 getter")
    void insufficientStock_threeArgs() {
        var ex = new InsufficientStockException(100L, 50, 10);
        assertThat(ex.getMessage()).isEqualTo("Insufficient stock for product 100: requested=50, available=10");
        assertThat(ex.getProductId()).isEqualTo(100L);
        assertThat(ex.getRequestedQuantity()).isEqualTo(50);
        assertThat(ex.getAvailableQuantity()).isEqualTo(10);
    }

    @Test @DisplayName("InsufficientStockException: 문자열 생성자 — getter 기본값")
    void insufficientStock_stringConstructor() {
        var ex = new InsufficientStockException("SKU-ABC 재고 부족");
        assertThat(ex.getMessage()).isEqualTo("SKU-ABC 재고 부족");
        assertThat(ex.getProductId()).isNull();
        assertThat(ex.getRequestedQuantity()).isZero();
        assertThat(ex.getAvailableQuantity()).isZero();
    }

    @Test @DisplayName("ProductNotFoundException: Long 생성자")
    void productNotFound_longConstructor() {
        var ex = new ProductNotFoundException(55L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("Product not found with id: 55");
    }

    @Test @DisplayName("ProductNotFoundException: 문자열 생성자")
    void productNotFound_stringConstructor() {
        var ex = new ProductNotFoundException("상품이 존재하지 않습니다");
        assertThat(ex.getMessage()).isEqualTo("상품이 존재하지 않습니다");
    }

    @Test @DisplayName("StockConcurrencyException: 문자열 생성자")
    void stockConcurrency_message() {
        var ex = new StockConcurrencyException("재고 동시성 충돌");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("재고 동시성 충돌");
        assertThat(ex.getCause()).isNull();
    }

    @Test @DisplayName("StockConcurrencyException: cause 전달")
    void stockConcurrency_withCause() {
        var cause = new RuntimeException("optimistic lock failure");
        var ex = new StockConcurrencyException("재시도 한계 초과", cause);
        assertThat(ex.getMessage()).isEqualTo("재시도 한계 초과");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
