package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class ProductFullTest {

    @Test @DisplayName("상품 생성") void create() {
        Product p = Product.create("테스트상품", "설명", new BigDecimal("10000"), 50);
        assertThat(p.getName()).isEqualTo("테스트상품");
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(p.getStockQuantity()).isEqualTo(50);
    }
    @Test @DisplayName("이름 빈값 예외") void create_blankName() {
        assertThatThrownBy(() -> Product.create("", "desc", new BigDecimal("100"), 1)).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("이름 200자 초과 예외") void create_longName() {
        assertThatThrownBy(() -> Product.create("a".repeat(201), "desc", new BigDecimal("100"), 1)).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("가격 음수 예외") void create_negativePrice() {
        assertThatThrownBy(() -> Product.create("상품", "desc", new BigDecimal("-1"), 1)).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("재고 음수 예외") void create_negativeStock() {
        assertThatThrownBy(() -> Product.create("상품", "desc", new BigDecimal("100"), -1)).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("재고 증가") void increaseStock() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 10);
        p.increaseStock(5);
        assertThat(p.getStockQuantity()).isEqualTo(15);
    }
    @Test @DisplayName("재고 증가 0이하 예외") void increaseStock_zero() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 10);
        assertThatThrownBy(() -> p.increaseStock(0)).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("재고 감소") void decreaseStock() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 10);
        p.decreaseStock(3);
        assertThat(p.getStockQuantity()).isEqualTo(7);
    }
    @Test @DisplayName("재고 부족 예외") void decreaseStock_insufficient() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 2);
        assertThatThrownBy(() -> p.decreaseStock(5)).isInstanceOf(InvalidProductStateException.class);
    }
    @Test @DisplayName("재고 0이면 품절 상태") void decreaseStock_outOfStock() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 3);
        p.decreaseStock(3);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }
    @Test @DisplayName("품절에서 재고 증가 시 ACTIVE 복원") void increaseStock_restoreActive() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        p.decreaseStock(1);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
        p.increaseStock(5);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }
    @Test @DisplayName("가격 변경") void changePrice() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        p.changePrice(new BigDecimal("200"));
        assertThat(p.getPrice()).isEqualByComparingTo("200");
    }
    @Test @DisplayName("가격 변경 음수 예외") void changePrice_negative() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        assertThatThrownBy(() -> p.changePrice(new BigDecimal("-1"))).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("비활성화") void deactivate() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        p.deactivate();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }
    @Test @DisplayName("단종") void discontinue() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        p.discontinue();
        assertThat(p.isDiscontinued()).isTrue();
    }
    @Test @DisplayName("단종 후 활성화 불가") void activate_discontinued() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        p.discontinue();
        assertThatThrownBy(p::activate).isInstanceOf(InvalidProductStateException.class);
    }
    @Test @DisplayName("판매 가능 여부") void isAvailableForSale() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 5);
        assertThat(p.isAvailableForSale()).isTrue();
        p.deactivate();
        assertThat(p.isAvailableForSale()).isFalse();
    }
    @Test @DisplayName("태그 관리") void tags() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        p.addTag(1L); p.addTag(2L); p.addTag(1L);
        assertThat(p.getTagIds()).hasSize(2);
        assertThat(p.hasTag(1L)).isTrue();
        p.removeTag(1L);
        assertThat(p.hasTag(1L)).isFalse();
        p.clearTags();
        assertThat(p.getTagIds()).isEmpty();
    }
    @Test @DisplayName("null 태그 예외") void addTag_null() {
        Product p = Product.create("상품", "desc", new BigDecimal("100"), 1);
        assertThatThrownBy(() -> p.addTag(null)).isInstanceOf(ProductInvariantViolationException.class);
    }
    @Test @DisplayName("정보 업데이트") void updateInfo() {
        Product p = Product.create("원래", "desc", new BigDecimal("100"), 1);
        p.updateInfo("변경됨", "새설명");
        assertThat(p.getName()).isEqualTo("변경됨");
        assertThat(p.getDescription()).isEqualTo("새설명");
    }
}
