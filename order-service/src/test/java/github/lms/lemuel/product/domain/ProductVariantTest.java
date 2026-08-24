package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductVariantTest {

    @Test
    @DisplayName("생성: 초기 재고 0 이면 OUT_OF_STOCK 으로 시작")
    void create_zeroStock_isOutOfStock() {
        var v = ProductVariant.create(1L, "SKU-RED-L", "색상:빨강/사이즈:L",
                new BigDecimal("1000"), 0);

        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);
        assertThat(v.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("생성: 초기 재고 양수 → ACTIVE")
    void create_positiveStock_isActive() {
        var v = ProductVariant.create(1L, "SKU-RED-L", "색상:빨강/사이즈:L",
                BigDecimal.ZERO, 100);

        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(v.isAvailable()).isTrue();
        assertThat(v.getStockQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("재고 차감 성공: 수량 감소 + 0 도달 시 OUT_OF_STOCK")
    void decrease_to_zero_marksOutOfStock() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 5);

        v.decreaseStock(5);

        assertThat(v.getStockQuantity()).isZero();
        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("재고 차감: 가용보다 많이 요청 시 InsufficientStockException")
    void decrease_exceedsStock() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 3);

        assertThatThrownBy(() -> v.decreaseStock(5))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("재고 부족");
    }

    @Test
    @DisplayName("재고 차감: 0 또는 음수 수량은 IllegalArgumentException")
    void decrease_invalidQuantity() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 5);

        assertThatThrownBy(() -> v.decreaseStock(0)).isInstanceOf(ProductInvariantViolationException.class);
        assertThatThrownBy(() -> v.decreaseStock(-1)).isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test
    @DisplayName("재고 차감: 단종된 SKU 는 IllegalStateException")
    void decrease_discontinuedSku() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 5);
        v.discontinue();

        assertThatThrownBy(() -> v.decreaseStock(1))
                .isInstanceOf(InvalidProductStateException.class)
                .hasMessageContaining("단종");
    }

    @Test
    @DisplayName("재고 증가: OUT_OF_STOCK 상태에서 재고 들어오면 ACTIVE 로 자동 복귀")
    void increase_recoversFromOutOfStock() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 0);
        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);

        v.increaseStock(10);

        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(v.getStockQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("유효 단가: 할인 없으면 기준가 + 옵션 추가금")
    void effectiveUnitPrice_additionalOnly() {
        var v = ProductVariant.create(1L, "SKU", "옵션", new BigDecimal("1000"), 10);

        assertThat(v.effectiveUnitPrice(new BigDecimal("10000")))
                .isEqualByComparingTo("11000");
    }

    @Test
    @DisplayName("유효 단가: 정액 할인(discountPrice) 이 추가금 반영 후 차감된다")
    void effectiveUnitPrice_fixedDiscount() {
        var v = ProductVariant.rehydrate(1L, 1L, "SKU", "옵션",
                new BigDecimal("1000"), new BigDecimal("500"), null, 10, 0L,
                ProductVariantStatus.ACTIVE, null, null);
        // 10000 + 1000 - 500 = 10500
        assertThat(v.effectiveUnitPrice(new BigDecimal("10000")))
                .isEqualByComparingTo("10500");
    }

    @Test
    @DisplayName("유효 단가: 정률 할인(discountRate %) 은 정액 할인 적용 후 금액에 적용, 원 단위 버림")
    void effectiveUnitPrice_rateDiscount() {
        var v = ProductVariant.rehydrate(1L, 1L, "SKU", "옵션",
                new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("10"), 10, 0L,
                ProductVariantStatus.ACTIVE, null, null);
        // (10000 + 1000 - 500) = 10500, 10% 할인 1050 → 9450
        assertThat(v.effectiveUnitPrice(new BigDecimal("10000")))
                .isEqualByComparingTo("9450");
    }

    @Test
    @DisplayName("유효 단가: 정률 할인 버림(FLOOR) — 1원 미만은 버린다")
    void effectiveUnitPrice_rateFloor() {
        var v = ProductVariant.rehydrate(1L, 1L, "SKU", "옵션",
                BigDecimal.ZERO, null, new BigDecimal("3"), 10, 0L,
                ProductVariantStatus.ACTIVE, null, null);
        // 9999 * 3% = 299.97 → FLOOR 299, 9999 - 299 = 9700
        assertThat(v.effectiveUnitPrice(new BigDecimal("9999")))
                .isEqualByComparingTo("9700");
    }

    @Test
    @DisplayName("유효 단가: 할인이 단가를 초과해도 0 미만으로 내려가지 않는다")
    void effectiveUnitPrice_flooredAtZero() {
        var v = ProductVariant.rehydrate(1L, 1L, "SKU", "옵션",
                BigDecimal.ZERO, new BigDecimal("99999"), null, 10, 0L,
                ProductVariantStatus.ACTIVE, null, null);
        assertThat(v.effectiveUnitPrice(new BigDecimal("10000")))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rehydrate: 영속 상태에서 복원 — version 보존")
    void rehydrate_preservesVersion() {
        var v = ProductVariant.rehydrate(99L, 1L, "SKU", "옵션",
                new BigDecimal("500"), 50, 7L,
                ProductVariantStatus.ACTIVE, null, null);

        assertThat(v.getId()).isEqualTo(99L);
        assertThat(v.getVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("생성 검증: optionName / sku 비어있으면 IllegalArgumentException")
    void create_validation() {
        assertThatThrownBy(() -> ProductVariant.create(1L, "", "옵션", BigDecimal.ZERO, 1))
                .isInstanceOf(ProductInvariantViolationException.class);
        assertThatThrownBy(() -> ProductVariant.create(1L, "SKU", "", BigDecimal.ZERO, 1))
                .isInstanceOf(ProductInvariantViolationException.class);
        assertThatThrownBy(() -> ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, -1))
                .isInstanceOf(ProductInvariantViolationException.class);
    }
}
